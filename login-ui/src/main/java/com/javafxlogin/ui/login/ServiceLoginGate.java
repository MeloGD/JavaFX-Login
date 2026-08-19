package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.AcknowledgePasswordReset;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.CompleteEnrolment;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.KeepSecret;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.MalformedMessageException;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.ReadSecret;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SecretRevealed;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.Session;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The gate a shipped product runs behind: it asks the AuthenticationService and believes nothing
 * else.
 *
 * <p>It asks to act as an {@link Role#OPERATOR}, because that is the Role this gate exists to
 * admit, and it is the service that decides whether the Account holds it. An Administrator
 * authenticating here is refused, and refused over there — this class could not grant them access
 * if it wanted to, and a patched copy of it could not either.
 *
 * <p>Be plain about how far that goes. A patched client can ask to act as an Administrator, be
 * granted a Session for it, and draw the feature's window anyway: nothing behind the gate asks what
 * the Session is for. What the refusal buys is that no Session for an Operator is ever issued to an
 * Administrator's password — and now that the SecretVault exists, that is worth something concrete
 * rather than something promised. The DataKey is unwrapped by an Operator's own password and is
 * never wrapped for an Administrator, so the window a patched client draws is a feature that cannot
 * read a single secret, and every attempt to read one is written to the record.
 *
 * <p>One connection is opened on the first attempt and kept, because a Session is bound to its
 * connection: the Session granted at the end of a run of attempts lives on the connection those
 * attempts were made over, and closing it is what ends the Session. A client that dies has the
 * kernel do that for it.
 *
 * <p>Synchronised, because two things now talk to the service at once: the window, and the
 * SessionGuard watching the Session the window produced. One request at a time is what the
 * connection under this promises, and taking the lock here is what keeps that promise.
 */
final class ServiceLoginGate implements LoginGate {

  private final Path socketPath;

  private ServiceClient client;

  ServiceLoginGate(Path socketPath) {
    this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
  }

  @Override
  public synchronized Admission admit(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    Response response = ask(new Authenticate(accountName, password, Role.OPERATOR));
    return switch (response) {
      case Granted granted ->
          new Admitted(new Session(granted.token()), granted.passwordResetAt());
      case Denied denied -> new NotAdmitted(denied.reason(), denied.lockedFor());
      // A readable answer that does not answer this question — a store the service cannot open,
      // say. It is not a refusal, and showing it as one would send the person to retype a
      // password that was never the problem.
      default -> throw unexpected("an authentication attempt", response);
    };
  }

  @Override
  public synchronized SessionStatus reportActivity(Session session) {
    Objects.requireNonNull(session, "session");
    return statusOf("a report of activity", ask(new ReportActivity(session.token())));
  }

  @Override
  public synchronized SessionStatus stillLive(Session session) {
    Objects.requireNonNull(session, "session");
    return statusOf("a question about a Session", ask(new AskIfSessionIsLive(session.token())));
  }

  @Override
  public synchronized void logOut(Session session) {
    Objects.requireNonNull(session, "session");
    Response response = ask(new Logout(session.token()));
    // A Session that had already ended is not a failure to end it: it is over, which is what was
    // asked for. Anything else is an answer to a question nobody put.
    if (!(response instanceof Ok || response instanceof SessionEnded)) {
      throw unexpected("a logout", response);
    }
  }

  @Override
  public synchronized void passwordResetNoticeWasRead(Session session) {
    Objects.requireNonNull(session, "session");
    Response response = ask(new AcknowledgePasswordReset(session.token()));
    // A Session that ended before the person got round to dismissing the notice is not a failure to
    // dismiss it: the notice goes with the window, and the service will say it again at the next
    // admission — which is exactly what it is for.
    if (!(response instanceof Ok || response instanceof SessionEnded)) {
      throw unexpected("a notice that was read", response);
    }
  }

  private static SessionStatus statusOf(String asked, Response response) {
    return switch (response) {
      case SessionLive live -> new SessionContinues(live.expiresIn());
      case SessionEnded ended -> new SessionOver(ended.reason());
      default -> throw unexpected(asked, response);
    };
  }

  @Override
  public synchronized SecretOutcome secretNamed(Session session, String name) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(name, "name");
    Response response = ask(new ReadSecret(session.token(), name));
    return switch (response) {
      case SecretRevealed revealed -> new SecretGiven(revealed.secret());
      case ErrorResponse error -> withheld("a request for a secret", error);
      case SessionEnded ignored -> new SecretWithheld(SecretWithheldReason.SESSION_OVER);
      // An admission, or an assessment: an answer to a question nobody put here. Handing one back
      // as an absent secret would have a host product connect to nothing and blame the credential.
      default -> throw unexpected("a request for a secret", response);
    };
  }

  @Override
  public synchronized SecretKeepingOutcome keepSecret(
      Session session, String name, char[] secret) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(secret, "secret");
    Response response = ask(new KeepSecret(session.token(), name, secret));
    return switch (response) {
      case Ok ignored -> new SecretKept();
      case ErrorResponse error -> withheld("a secret to keep", error);
      case SessionEnded ignored -> new SecretWithheld(SecretWithheldReason.SESSION_OVER);
      default -> throw unexpected("a secret to keep", response);
    };
  }

  /**
   * What the service refused a Vault request with, in the words a host product reads.
   *
   * <p>Two of these are not refusals at all but a service that could not read its own files, and
   * they leave as {@link ServiceUnreachableException} for the same reason a store that will not open
   * does at the first run: nothing was decided about this request, and the remedy is not the
   * caller's.
   */
  private static SecretWithheld withheld(String asked, ErrorResponse error) {
    return switch (error.code()) {
      case NO_SUCH_SECRET -> new SecretWithheld(SecretWithheldReason.NO_SUCH_SECRET);
      case NO_VAULT_ACCESS -> new SecretWithheld(SecretWithheldReason.NO_VAULT_ACCESS);
      case NOT_AN_OPERATOR -> new SecretWithheld(SecretWithheldReason.NOT_AN_OPERATOR);
      case STORE_UNAVAILABLE, VAULT_UNAVAILABLE ->
          throw new ServiceUnreachableException(
              "The AuthenticationService could not reach the files it owns");
      // Every one of these answers a request about an Account, a first run, or a file to copy the
      // record into. None of them is an answer to a question about a secret.
      case ADMINISTRATOR_EXISTS,
              NOT_MACHINE_ADMINISTRATOR,
              NOT_ADMINISTRATOR,
              NO_SUCH_ACCOUNT,
              ACCOUNT_EXISTS,
              CANNOT_ENROL_THE_ADMINISTRATOR,
              CANNOT_DELETE_THE_ADMINISTRATOR,
              EXPORT_DESTINATION_REFUSED,
              EXPORT_FAILED ->
          throw unexpected(asked, error);
    };
  }

  @Override
  public synchronized boolean firstRunNeeded() {
    Response response = ask(new AskIfBootstrapNeeded());
    if (response instanceof BootstrapNeeded needed) {
      return needed.needed();
    }
    throw unexpected("a question about the first run", response);
  }

  @Override
  public synchronized FirstRunOutcome createAdministrator(
      String administratorName, char[] password) {
    Objects.requireNonNull(administratorName, "administratorName");
    Objects.requireNonNull(password, "password");

    Response response = ask(new Bootstrap(administratorName, password));
    return switch (response) {
      case Ok ignored -> new AdministratorCreated();
      case PolicyRefused refused -> new PolicyRefusal(refused.violations());
      case ErrorResponse error -> refusalOf(error);
      // A Session for a wizard that was never asked to admit anyone, or an assessment nobody
      // asked for. Neither is an outcome, and showing one as a refusal would send the person to
      // retype a name that was never the problem.
      default -> throw unexpected("an attempt to create the Administrator", response);
    };
  }

  @Override
  public synchronized EnrolmentOutcome completeEnrolment(
      String accountName, char[] secret, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(password, "password");

    Response response = ask(new CompleteEnrolment(accountName, secret, password));
    return switch (response) {
      case Ok ignored -> new Enrolled();
      case PolicyRefused refused -> new PolicyRefusal(refused.violations());
      case Denied denied -> new EnrolmentRefused(denied.reason(), denied.lockedFor());
      // A Session for a screen that asked for none, or an answer to a question nobody put here.
      // Showing one as a refusal would send the person back to whoever gave them the secret over
      // something that was never about the secret.
      default -> throw unexpected("an enrolment", response);
    };
  }

  private FirstRunOutcome refusalOf(ErrorResponse error) {
    return switch (error.code()) {
      case ADMINISTRATOR_EXISTS -> new FirstRunRefused(FirstRunRefusedReason.ADMINISTRATOR_EXISTS);
      case NOT_MACHINE_ADMINISTRATOR ->
          new FirstRunRefused(FirstRunRefusedReason.NOT_MACHINE_ADMINISTRATOR);
      // The service could not read its own store. Nothing was decided about this person, and the
      // remedy is not theirs — it is the same nothing-to-be-done as an unreachable service.
      case STORE_UNAVAILABLE ->
          throw new ServiceUnreachableException(
              "The AuthenticationService could not reach its CredentialStore");
      // Every one of these is answered to a request made from an Administrator's Session — about
      // an Account, or about a file to copy the record into. The first run is neither: it carries
      // no Session at all, being what creates the Account that can hold one, and it asks for
      // nothing to be written anywhere. Reaching here means the service answered a question nobody
      // asked.
      case NOT_ADMINISTRATOR,
              NOT_AN_OPERATOR,
              NO_SUCH_ACCOUNT,
              ACCOUNT_EXISTS,
              CANNOT_ENROL_THE_ADMINISTRATOR,
              CANNOT_DELETE_THE_ADMINISTRATOR,
              NO_VAULT_ACCESS,
              NO_SUCH_SECRET,
              VAULT_UNAVAILABLE,
              EXPORT_DESTINATION_REFUSED,
              EXPORT_FAILED ->
          throw unexpected("an attempt to create the Administrator", error);
    };
  }

  private Response ask(Request request) {
    try {
      return connection().send(request);
    } catch (IOException | MalformedMessageException e) {
      drop();
      throw new ServiceUnreachableException(
          "Could not reach the AuthenticationService at " + socketPath, e);
    }
  }

  private static ServiceUnreachableException unexpected(String asked, Response response) {
    return new ServiceUnreachableException(
        "The AuthenticationService answered "
            + asked
            + " with a "
            + response.getClass().getSimpleName());
  }

  private ServiceClient connection() throws IOException {
    if (client == null || !client.isOpen()) {
      client = ServiceClient.connect(socketPath);
    }
    return client;
  }

  private void drop() {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (IOException ignored) {
      // Already reporting a worse failure to the caller.
    } finally {
      client = null;
    }
  }
}
