package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Assess;
import com.javafxlogin.core.ipc.Assessed;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.machine.MachineAdministrators;
import com.javafxlogin.core.policy.AccountPolicy;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.session.SessionToken;
import com.javafxlogin.core.store.CredentialStore;
import com.javafxlogin.core.store.CredentialStoreException;
import com.javafxlogin.core.store.SchemaTooNewException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * The privileged process: it owns the CredentialStore and is the only party that can verify a
 * password.
 *
 * <p>This class is the request handler, addressed in process. Nothing here knows about sockets; the
 * transport hands it request objects and returns what comes back. That is also what makes the
 * handler testable — running as root changes which files may be opened, and changes nothing about
 * whether a wrong password is refused.
 *
 * <p>Not to be confused with the {@link Authenticator}, which is the component that verifies a
 * password. The names are distinct on purpose.
 */
public final class AuthenticationService implements AutoCloseable {

  /**
   * The file a deployment writes beside its store to refuse names of its own — the product's name
   * under another brand, the names of the systems it talks to. It need not exist. Living beside the
   * store puts it where only the account this service runs as can write it.
   */
  private static final String DEPLOYMENT_BLOCKED_NAMES = "blocked-account-names.txt";

  private final CredentialStore store;
  private final Authenticator authenticator;
  private final AccountPolicy policy;
  private final MachineAdministrators administrators;
  private final SecureRandom random;

  private AuthenticationService(
      CredentialStore store,
      Authenticator authenticator,
      AccountPolicy policy,
      MachineAdministrators administrators) {
    this.store = store;
    this.authenticator = authenticator;
    this.policy = policy;
    this.administrators = administrators;
    this.random = new SecureRandom();
  }

  /**
   * Opens the service as it ships: hashing at {@link Argon2Parameters#PRODUCTION}, and asking this
   * platform who administers the machine. This is the overload production code calls, so that
   * reaching the OWASP minimums is the default rather than something every caller has to remember.
   *
   * @throws SchemaTooNewException if the store was written by a build that understood a later
   *     schema — the service refuses to start rather than corrupt it
   */
  public static AuthenticationService open(Path storeFile) {
    return open(storeFile, Argon2Parameters.PRODUCTION);
  }

  /**
   * As {@link #open(Path)}, with the hashing parameters named explicitly. Tests use this to
   * provision Accounts cheaply; the verification path is the same either way, because the
   * parameters travel inside each stored PHC hash.
   */
  public static AuthenticationService open(Path storeFile, Argon2Parameters parameters) {
    return open(storeFile, parameters, MachineAdministrators.forCurrentPlatform());
  }

  /**
   * As {@link #open(Path, Argon2Parameters)}, with the machine's administrators named explicitly.
   *
   * <p>A suite cannot arrange for the account it runs as to be an administrator of the machine, and
   * one that asserted against the real group database would be asserting about the developer rather
   * than about this code. Naming them here is what lets both answers be tested.
   */
  public static AuthenticationService open(
      Path storeFile, Argon2Parameters parameters, MachineAdministrators administrators) {
    Objects.requireNonNull(storeFile, "storeFile");
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(administrators, "administrators");

    CredentialStore store = CredentialStore.openOrCreate(storeFile);
    try {
      return new AuthenticationService(
          store,
          new Authenticator(parameters),
          AccountPolicy.bundledExtendedBy(storeFile.resolveSibling(DEPLOYMENT_BLOCKED_NAMES)),
          administrators);
    } catch (RuntimeException e) {
      store.close();
      throw e;
    }
  }

  /**
   * Answers a request. Every request is answered: a store that cannot be read becomes an {@link
   * ErrorResponse} rather than an exception thrown at whatever is carrying the request, because the
   * caller is owed an outcome and must not be told which failure produced it.
   *
   * <p>The connection arrives with the request because one thing about the peer cannot be asked
   * for afterwards: who the operating system says they are. Only {@link Bootstrap} reads it.
   *
   * <p>Synchronised, because the transport calls this from one thread per connection while the
   * CredentialStore behind it holds a single JDBC connection. Serialising the privileged process's
   * work is also the conservative reading of a machine with one person at the keyboard: nothing
   * here is worth the concurrency, and an Argon2id verification is meant to be slow.
   */
  public synchronized Response handle(Request request, ConnectionHandle connection) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(connection, "connection");
    try {
      return switch (request) {
        case Bootstrap bootstrap -> bootstrap(bootstrap, connection);
        case AskIfBootstrapNeeded ignored -> new BootstrapNeeded(!store.hasAdministrator());
        case Authenticate authenticate -> authenticate(authenticate);
        case Assess assess -> assess(assess);
      };
    } catch (CredentialStoreException e) {
      return new ErrorResponse(ErrorCode.STORE_UNAVAILABLE);
    }
  }

  /**
   * Creates the single Administrator, behind two guards rather than one.
   *
   * <p>Who is asking is settled before what the store holds, so that a peer with no business here
   * is told the same thing on a fresh install as on one that was set up years ago. Nothing else in
   * this class asks who the peer is: authenticating is answered for anyone, because the password is
   * the proof. This request has no password to prove anything with — it is the one that creates the
   * first one — so the operating system's word about the peer is all there is to go on.
   */
  private Response bootstrap(Bootstrap request, ConnectionHandle connection) {
    if (!administersThisMachine(connection)) {
      return new ErrorResponse(ErrorCode.NOT_MACHINE_ADMINISTRATOR);
    }
    if (store.hasAdministrator()) {
      return new ErrorResponse(ErrorCode.ADMINISTRATOR_EXISTS);
    }
    Assessment assessment = policy.assess(request.administratorName(), request.password());
    if (!assessment.violations().isEmpty()) {
      return new PolicyRefused(assessment.violations());
    }
    String hash = authenticator.hash(request.password());
    store.insert(
        new Account(request.administratorName(), Role.ADMINISTRATOR, hash, assessment.strength()));
    return new Ok();
  }

  /**
   * Whether the operating system says the peer administers this machine. A peer it will not name
   * at all counts as one it named and refused: an absent answer is never read as permission.
   */
  private boolean administersThisMachine(ConnectionHandle connection) {
    return connection.peer().map(administrators::includes).orElse(false);
  }

  /**
   * Answers what the policy makes of a proposed name and password, and does nothing else. It reads
   * no Account and creates none, so the answer is the same whether or not the name is taken.
   */
  private Response assess(Assess request) {
    return new Assessed(policy.assess(request.accountName(), request.password()));
  }

  private Response authenticate(Authenticate request) {
    Optional<Account> account = store.findByName(request.accountName());

    // The absent branch spends the same Argon2id work as the present one, so a stopwatch at the
    // login screen cannot name which Accounts are real.
    boolean verified =
        account
            .map(found -> authenticator.verify(request.password(), found.passwordHash()))
            .orElseGet(() -> authenticator.verifyAgainstAbsentAccount(request.password()));

    // The Account is named rather than assumed present: nothing but the reference hash can verify
    // when there is no Account, and a build that ever made that untrue should fail here as a
    // refusal rather than reach the line below with nothing in hand.
    if (!verified || account.isEmpty()) {
      return new Denied(DeniedReason.AUTH_FAILED);
    }

    // An Administrator asking to act as an Operator is refused here, in the privileged process,
    // and refused in the same words as a wrong password: telling the two apart would name the one
    // Account whose Role an attacker can guess. The check follows the verification rather than
    // replacing it, so the refusal costs what every other refusal costs.
    if (account.get().role() != request.requestedRole()) {
      return new Denied(DeniedReason.AUTH_FAILED);
    }
    return new Granted(SessionToken.generate(random));
  }

  /** Synchronised with {@link #handle}, so that shutting down waits for the answer in flight. */
  @Override
  public synchronized void close() {
    store.close();
  }
}
