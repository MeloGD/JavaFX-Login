package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.MalformedMessageException;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.session.Session;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The gate a shipped product runs behind: it asks the AuthenticationService and believes nothing
 * else.
 *
 * <p>It asks to act as an {@link Role#OPERATOR}, because that is the Role this gate exists to
 * admit, and it is the service that decides whether the Account holds it. An Administrator
 * authenticating here is refused, and refused over there — this class could not grant them access
 * if it wanted to, and a patched copy of it could not either.
 *
 * <p>Be plain about how far that goes today. A patched client can ask to act as an Administrator,
 * be granted a Session for it, and draw the feature's window anyway: nothing behind the gate yet
 * asks what the Session is for. What the refusal buys now is that no Session for an Operator is
 * ever issued to an Administrator's password, which is what the SecretVault will need — the
 * DataKey is unwrapped by an Operator's own password and is never wrapped for an Administrator, so
 * a window drawn without one shows a feature that cannot reach a single secret. Until that ticket
 * lands, the exclusion is enforced where it will keep being enforced, and it is worth no more than
 * this paragraph says.
 *
 * <p>One connection is opened on the first attempt and kept, because a Session is bound to its
 * connection: the Session granted at the end of a run of attempts lives on the connection those
 * attempts were made over, and closing it is what ends the Session. A client that dies has the
 * kernel do that for it.
 *
 * <p>Not thread-safe: the window makes one attempt at a time.
 */
final class ServiceLoginGate implements LoginGate {

  private final Path socketPath;

  private ServiceClient client;

  ServiceLoginGate(Path socketPath) {
    this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
  }

  @Override
  public Optional<Session> admit(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    Response response = ask(new Authenticate(accountName, password, Role.OPERATOR));
    return switch (response) {
      case Granted granted -> Optional.of(new Session(granted.token()));
      case Denied ignored -> Optional.empty();
      // A readable answer that does not answer this question — a store the service cannot open,
      // say. It is not a refusal, and showing it as one would send the person to retype a
      // password that was never the problem.
      default -> throw unexpected("an authentication attempt", response);
    };
  }

  @Override
  public boolean bootstrapNeeded() {
    Response response = ask(new AskIfBootstrapNeeded());
    if (response instanceof BootstrapNeeded needed) {
      return needed.needed();
    }
    throw unexpected("a question about the first run", response);
  }

  @Override
  public FirstRunOutcome createAdministrator(String administratorName, char[] password) {
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

  private FirstRunOutcome refusalOf(ErrorResponse error) {
    return switch (error.code()) {
      case ADMINISTRATOR_EXISTS -> new WizardRefused(WizardRefusedReason.ADMINISTRATOR_EXISTS);
      case NOT_MACHINE_ADMINISTRATOR ->
          new WizardRefused(WizardRefusedReason.NOT_MACHINE_ADMINISTRATOR);
      // The service could not read its own store. Nothing was decided about this person, and the
      // remedy is not theirs — it is the same nothing-to-be-done as an unreachable service.
      case STORE_UNAVAILABLE ->
          throw new ServiceUnreachableException(
              "The AuthenticationService could not reach its CredentialStore");
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
