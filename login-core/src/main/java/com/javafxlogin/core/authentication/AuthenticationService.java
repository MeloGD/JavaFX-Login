package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEvent;
import com.javafxlogin.core.audit.AuthenticationEventArchive;
import com.javafxlogin.core.audit.AuthenticationEventExport;
import com.javafxlogin.core.audit.AuthenticationEventLog;
import com.javafxlogin.core.audit.AuthenticationEventType;
import com.javafxlogin.core.audit.FileAuthenticationEventLog;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.ipc.AccountsListed;
import com.javafxlogin.core.ipc.AcknowledgePasswordReset;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Assess;
import com.javafxlogin.core.ipc.Assessed;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.AuthenticationEventsExported;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.ChangeInactivityPeriod;
import com.javafxlogin.core.ipc.ChangeOwnPassword;
import com.javafxlogin.core.ipc.ClearLockout;
import com.javafxlogin.core.ipc.CompleteEnrolment;
import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.DeleteAccount;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.ExportAuthenticationEvents;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.InitiateReset;
import com.javafxlogin.core.ipc.KeepSecret;
import com.javafxlogin.core.ipc.ListAccounts;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.ReadSecret;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SecretRevealed;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.machine.MachineAdministrators;
import com.javafxlogin.core.policy.AccountPolicy;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionClock;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import com.javafxlogin.core.store.CredentialStore;
import com.javafxlogin.core.store.CredentialStoreException;
import com.javafxlogin.core.store.SchemaTooNewException;
import com.javafxlogin.core.vault.SecretVault;
import com.javafxlogin.core.vault.UnlockedVault;
import com.javafxlogin.core.vault.VaultException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

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

  /**
   * Where AuthenticationEvents are written, beside the store for the same reason: only the account
   * this service runs as may write in that directory. The rotated files are named after it, and the
   * key their chain is computed under lives next to them.
   */
  private static final String EVENT_LOG = "authentication-events.csv";

  /**
   * The key the record's chain is computed under, made on the first event and read on every start
   * after that. Beside the record for the same reason the record is beside the store, and it is
   * what puts an edit of the record beyond an Administrator who cannot read that directory.
   */
  private static final String EVENT_LOG_KEY = "authentication-events.key";

  /**
   * The SecretVault, beside the store rather than inside it: ADR-0004 keeps the two files apart
   * because they answer different questions, face different attackers and change at different rates.
   */
  private static final String SECRET_VAULT = "secrets.db";

  /**
   * The MachineKey, which holds the second wrapped copy of the DataKey. Beside the Vault for the
   * reason the chain's key is beside the record — the directory is readable only by the account this
   * service runs as — and it is what lets an Operator be provisioned with nobody present.
   */
  private static final String SECRET_VAULT_KEY = "secrets.key";

  private final CredentialStore store;
  private final SecretVault vault;
  private final Authenticator authenticator;
  private final AccountPolicy policy;
  private final MachineAdministrators administrators;
  private final Sessions sessions;
  private final Lockouts lockouts;
  private final Enrolments enrolments;
  private final SessionClock clock;
  private final AuthenticationEventLog events;
  private final AuthenticationEventArchive archive;
  private final Path ownDirectory;
  private final SecureRandom random;

  private AuthenticationService(
      CredentialStore store,
      SecretVault vault,
      Authenticator authenticator,
      AccountPolicy policy,
      MachineAdministrators administrators,
      SessionClock clock,
      AuthenticationEventLog events,
      AuthenticationEventArchive archive,
      Path ownDirectory) {
    this.store = store;
    this.vault = vault;
    this.authenticator = authenticator;
    this.policy = policy;
    this.administrators = administrators;
    this.sessions = new Sessions(clock);
    this.lockouts = new Lockouts(store, clock);
    this.clock = clock;
    this.events = events;
    this.archive = archive;
    this.ownDirectory = ownDirectory;
    this.random = new SecureRandom();
    this.enrolments = new Enrolments(store, clock, random);
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
    return open(storeFile, parameters, administrators, SessionClock.system());
  }

  /**
   * As {@link #open(Path, Argon2Parameters, MachineAdministrators)}, with the clocks a Session is
   * timed against named explicitly.
   *
   * <p>A suite cannot wait out an inactivity period, move the machine's clock, or suspend the
   * machine, and one that tried would be testing the operating system rather than these rules.
   * Naming the clocks here is what lets every one of them be asserted in milliseconds.
   */
  public static AuthenticationService open(
      Path storeFile,
      Argon2Parameters parameters,
      MachineAdministrators administrators,
      SessionClock clock) {
    Objects.requireNonNull(storeFile, "storeFile");
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(administrators, "administrators");
    Objects.requireNonNull(clock, "clock");

    CredentialStore store = CredentialStore.openOrCreate(storeFile);
    SecretVault vault =
        openTheVaultBeside(store, storeFile, parameters);
    try {
      // One object behind two interfaces, on purpose. Everything that records an event holds the
      // write-only one; only the export holds the one that can read a file back.
      FileAuthenticationEventLog log =
          new FileAuthenticationEventLog(
              storeFile.resolveSibling(EVENT_LOG), storeFile.resolveSibling(EVENT_LOG_KEY));
      return new AuthenticationService(
          store,
          vault,
          new Authenticator(parameters),
          AccountPolicy.bundledExtendedBy(storeFile.resolveSibling(DEPLOYMENT_BLOCKED_NAMES)),
          administrators,
          clock,
          log,
          log,
          storeFile.toAbsolutePath().normalize().getParent());
    } catch (RuntimeException e) {
      vault.close();
      store.close();
      throw e;
    }
  }

  /**
   * Opens the SecretVault, and closes the store again if it will not open.
   *
   * <p>A service that started without its Vault would be one that admits Operators to a
   * ProtectedFeature that cannot reach a single secret, and does so silently. Refusing to start says
   * it once, where somebody is looking.
   */
  private static SecretVault openTheVaultBeside(
      CredentialStore store, Path storeFile, Argon2Parameters parameters) {
    try {
      return SecretVault.openOrCreate(
          storeFile.resolveSibling(SECRET_VAULT),
          storeFile.resolveSibling(SECRET_VAULT_KEY),
          parameters);
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
        case Authenticate authenticate -> authenticate(authenticate, connection);
        case Assess assess -> assess(assess);
        case ReportActivity report -> reportActivity(report, connection);
        case AskIfSessionIsLive ask -> askIfSessionIsLive(ask, connection);
        case Logout logout -> logOut(logout, connection);
        case ChangeInactivityPeriod change -> changeInactivityPeriod(change, connection);
        case ClearLockout clear -> clearLockout(clear, connection);
        case ExportAuthenticationEvents export -> exportAuthenticationEvents(export, connection);
        case CreateAccount create -> createAccount(create, connection);
        case InitiateReset reset -> initiateReset(reset, connection);
        case CompleteEnrolment complete -> completeEnrolment(complete);
        case AcknowledgePasswordReset seen -> acknowledgePasswordReset(seen, connection);
        case ChangeOwnPassword change -> changeOwnPassword(change, connection);
        case DeleteAccount delete -> deleteAccount(delete, connection);
        case ListAccounts list -> listAccounts(list, connection);
        case ReadSecret read -> readSecret(read, connection);
        case KeepSecret keep -> keepSecret(keep, connection);
      };
    } catch (CredentialStoreException e) {
      return new ErrorResponse(ErrorCode.STORE_UNAVAILABLE);
    } catch (VaultException e) {
      // Said as one code, like a store that will not answer: what a client can do about either is
      // retry or give up, and which file failed is this service's business and not theirs.
      return new ErrorResponse(ErrorCode.VAULT_UNAVAILABLE);
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
   *
   * <p>Only the creation is recorded, and against the name the Administrator now holds. A refused
   * attempt has nothing but a typed string to be recorded against, and story 77 keeps typed strings
   * out of the record: whoever mistypes a password into the name box would otherwise put it there
   * permanently, at the one moment the machine is least supervised.
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
    // Nothing wraps the DataKey for this Account, here or anywhere: the Administrator is the one
    // Role that never enrols, and enrolment is the only thing that writes a wrap. That absence is
    // what makes the exclusion from the Vault cryptographic rather than a check — there is no copy
    // of the DataKey this password could derive the key to.
    store.insert(
        new Account(request.administratorName(), Role.ADMINISTRATOR, hash, assessment.strength()));
    record(AuthenticationEventType.ADMINISTRATOR_CREATED, request.administratorName());
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

  private Response authenticate(Authenticate request, ConnectionHandle connection) {
    // The machine already has someone on it. Refused before any Account is looked at, so this
    // costs no Argon2id work and reveals nothing about any Account: a live Session is already
    // visible to anyone who can see the screen it is open on. The live one is kept, so a second
    // person typing a password cannot throw out the person working.
    if (theMachineIsBusy()) {
      // Recorded against nobody, because nobody has been looked up: the name that was typed is a
      // typed string until an Account is found holding it, and story 77 keeps those out of here.
      record(
          AuthenticationEventType.AUTHENTICATION_REFUSED_SESSION_ALREADY_LIVE,
          AuthenticationEvent.NO_ACCOUNT);
      return Denied.because(DeniedReason.SESSION_ALREADY_LIVE);
    }

    Optional<Account> account = store.findByName(request.accountName());

    // The absent branch spends the same Argon2id work as the present one, so a stopwatch at the
    // login screen cannot name which Accounts are real. An Account awaiting enrolment goes down
    // that same branch: it has no hash to be right about, and a refusal that came back in no time
    // at all would name it as an Account before the answer below says so in words.
    boolean verified =
        account
            .map(found -> verified(found, request.password()))
            .orElseGet(() -> authenticator.verifyAgainstAbsentAccount(request.password()));

    // A Lockout is applied after the verification rather than instead of it, so that a refused
    // attempt costs the same whether the Account is locked, wrong or absent. Skipping the work
    // would save this service nothing it can bank anyway: every attempt costs one verification,
    // whatever name it names, which is what the absent branch above is for.
    Optional<Duration> refusedFor = account.flatMap(found -> lockouts.refusalOf(found.name()));
    if (refusedFor.isPresent()) {
      record(AuthenticationEventType.AUTHENTICATION_REFUSED_LOCKED_OUT, account.get().name());
      return Denied.lockedFor(refusedFor.get());
    }

    // The absent Account is settled before the verification is read, rather than beside it: what
    // was verified there was the reference hash, which nothing can be right about. Everything
    // below therefore has an Account in hand.
    if (account.isEmpty()) {
      record(
          AuthenticationEventType.AUTHENTICATION_FAILED_NO_SUCH_ACCOUNT,
          AuthenticationEvent.NO_ACCOUNT);
      return Denied.because(DeniedReason.AUTH_FAILED);
    }

    // An Account nobody has enrolled against yet, or one an Administrator has just taken the
    // password of. There is nothing here a password could be right about, so the person is sent to
    // the screen where the secret they were handed is worth something — rather than to retype a
    // password they were never given. It is not counted as a failure against the Account: whoever
    // guessed the name first would otherwise be able to lock an Operator out of their own
    // enrolment, and there was no password to be wrong about.
    if (account.get().isAwaitingEnrolment()) {
      record(
          AuthenticationEventType.AUTHENTICATION_REFUSED_ENROLMENT_REQUIRED, account.get().name());
      return Denied.because(DeniedReason.ENROLMENT_REQUIRED);
    }

    if (!verified) {
      return refuse(account.get(), AuthenticationEventType.AUTHENTICATION_FAILED_WRONG_PASSWORD);
    }

    // An Administrator asking to act as an Operator is refused here, in the privileged process,
    // and refused in the same words as a wrong password: telling the two apart would name the one
    // Account whose Role an attacker can guess. The check follows the verification rather than
    // replacing it, so the refusal costs what every other refusal costs — and it is counted
    // against the Account like any other failure, because an Account that could never be locked
    // out would be one an attacker could tell from every other Account by failing at it all day.
    // The record says which of the two it was, because the record is read by whoever administers
    // the deployment and they are owed the difference the login screen may not be told.
    if (account.get().role() != request.requestedRole()) {
      return refuse(account.get(), AuthenticationEventType.AUTHENTICATION_FAILED_WRONG_ROLE);
    }

    lockouts.succeeded(account.get().name());

    // Read here, because this is the moment somebody has proved they hold the Account, and so the
    // only moment a reset they did not ask for can be reported to the person it was done to rather
    // than to whoever walked past the screen. Reading it does not spend it: it is said again on the
    // next admission, and the one after that, until an AcknowledgePasswordReset says it was read.
    Optional<Instant> passwordResetAt = enrolments.resetToDeclareFor(account.get().name());

    // The moment ADR-0004 is about. The password is still in hand here and nowhere else, and this is
    // the one place it can derive the key that unwraps this Account's copy of the DataKey. Nothing
    // below reads the outcome as permission: a Session is granted either way, and what a Session
    // without a key cannot do is read a secret.
    //
    // It costs a second Argon2id derivation on top of the verification above, which is the price of
    // the two being cryptographically separate. Only a successful authentication pays it, so the
    // equality between a wrong password and a name nobody holds — one derivation each — is
    // untouched.
    Optional<UnlockedVault> unlocked = theVaultOf(account.get(), request.password());

    SessionToken token = SessionToken.generate(random);
    sessions.open(token, account.get().name(), account.get().role(), connection, unlocked);

    // Recorded once the Session exists, not once the password checked out. Everything else here
    // records a refusal, which is over by the time it is written; an admission is not over until
    // there is a Session, and the record says what happened rather than what was about to.
    record(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, account.get().name());
    return new Granted(token, passwordResetAt);
  }

  /**
   * Creates an Account, without ever being told what it will authenticate with.
   *
   * <p>This is ASVS 5.0 §6.4.6 and the whole of what issue #10 is for: the Administrator says who
   * exists and the person who will use the Account says what its password is. What comes back is a
   * secret to hand over, shown once and never readable again.
   *
   * <p>ADR-0005 is honest about the size of this. It does not stop a compromised Administrator
   * reaching the SecretVault — they can create an Operator here and enrol it themselves, which is
   * two requests. What it removes is the quieter thing: an Administrator taking over an Account
   * somebody is already using, handing it back, and going on knowing a password that stays in use.
   */
  private Response createAccount(CreateAccount request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(), connection, live -> onlyAnAdministrator(live, () -> createFor(request)));
  }

  private Response createFor(CreateAccount request) {
    if (request.role() == Role.ADMINISTRATOR) {
      return new ErrorResponse(ErrorCode.CANNOT_ENROL_THE_ADMINISTRATOR);
    }
    List<PolicyViolation> violations = policy.violationsOfName(request.accountName());
    if (!violations.isEmpty()) {
      return new PolicyRefused(violations);
    }
    // Said plainly, unlike anything the login screen answers: the caller holds a Session this
    // service granted in the Role that manages Accounts, and one who is not told would walk away
    // handing somebody an enrolment secret for an Account that was never created.
    if (store.findByName(request.accountName()).isPresent()) {
      return new ErrorResponse(ErrorCode.ACCOUNT_EXISTS);
    }
    Enrolments.Issued secret = enrolments.create(request.accountName(), request.role());
    record(AuthenticationEventType.ACCOUNT_CREATED, request.accountName());
    return issued(secret, request.accountName());
  }

  /**
   * Takes an Account's password away and issues a secret in its place — a reset for somebody who
   * forgot theirs, and the same request again where a secret was lost or ran out.
   */
  private Response initiateReset(InitiateReset request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnAdministrator(live, () -> resetFor(request.accountName())));
  }

  private Response resetFor(String accountName) {
    Optional<Account> account = store.findByName(accountName);
    if (account.isEmpty()) {
      return new ErrorResponse(ErrorCode.NO_SUCH_ACCOUNT);
    }
    // The Administrator's own password is chosen at the first-run wizard by whoever will use it,
    // and there is nobody to hand a secret to. An Administrator who could reset it from a Session
    // would be one whose password an attacker holding that Session need never have known.
    if (account.get().role() == Role.ADMINISTRATOR) {
      return new ErrorResponse(ErrorCode.CANNOT_ENROL_THE_ADMINISTRATOR);
    }
    boolean thereWasAPasswordToTakeAway = !account.get().isAwaitingEnrolment();
    Enrolments.Issued secret = enrolments.issueFor(account.get());
    // The wrapped copy of the DataKey goes with the password it was under. A reset that left it
    // would be a password an Administrator has taken away that still opens the Vault, which is the
    // half-measure ASVS 5.0 §6.4.6 exists to refuse; the enrolment that follows writes a new one
    // from the machine's copy, so nothing is lost by destroying this one now.
    vault.destroyWrapFor(accountName);
    if (thereWasAPasswordToTakeAway) {
      record(AuthenticationEventType.PASSWORD_RESET_INITIATED, accountName);
    }
    return issued(secret, accountName);
  }

  /**
   * Turns a one-time secret into the password its holder chose, which is the only way an Operator
   * ever comes to have one.
   *
   * <p>It carries no Session, because whoever sends it cannot have one: the Account has no password
   * to have authenticated with. Everything that is not the outstanding secret is refused in the same
   * words — a wrong secret, an expired one, one that has been used, and an Account that is waiting
   * for nothing — and a refusal counts against the Account like any other failure. That is the one
   * place guessing at this Account can happen, so leaving it uncounted would make waiting for
   * enrolment the one state in which guessing is free.
   */
  private Response completeEnrolment(CompleteEnrolment request) {
    Optional<Account> account = store.findByName(request.accountName());

    Optional<Duration> refusedFor = account.flatMap(found -> lockouts.refusalOf(found.name()));
    if (refusedFor.isPresent()) {
      record(AuthenticationEventType.AUTHENTICATION_REFUSED_LOCKED_OUT, account.get().name());
      return Denied.lockedFor(refusedFor.get());
    }

    if (account.isEmpty()) {
      record(AuthenticationEventType.ENROLMENT_FAILED, AuthenticationEvent.NO_ACCOUNT);
      return Denied.because(DeniedReason.AUTH_FAILED);
    }
    if (!enrolments.accepts(request.accountName(), request.secret())) {
      return refuse(account.get(), AuthenticationEventType.ENROLMENT_FAILED);
    }

    // The name is not assessed again. It belongs to an Account that already exists and passed the
    // rules when it was created, and the person here cannot change it.
    Assessment assessment = policy.assessPassword(request.password());
    if (!assessment.violations().isEmpty()) {
      // The secret survives a refused password. It is consumed by an enrolment that completed, not
      // by an attempt at one, or a person who chose a password one character short would have to go
      // back to the Administrator for another secret.
      return new PolicyRefused(assessment.violations());
    }

    // Criterion 4: the DataKey is wrapped under a key derived from the password chosen here, with a
    // salt and parameters of the Vault's own — the stored authentication hash below is never key
    // material. It is written before the password is, so that an Account which authenticates always
    // has a wrap: were it the other way round, a failure in between would leave an Operator who can
    // reach the feature and none of the secrets behind it, and nothing would say so.
    //
    // The secret survives a Vault that will not write, because nothing here has consumed it yet.
    vault.wrapFor(request.accountName(), request.password());
    enrolments.completedBy(
        request.accountName(), authenticator.hash(request.password()), assessment.strength());
    lockouts.succeeded(request.accountName());
    record(AuthenticationEventType.ENROLMENT_COMPLETED, request.accountName());
    return new Ok();
  }

  /**
   * The person holding the Session says they have read the notice about their password having been
   * reset, so the service stops saying it.
   *
   * <p>The Session is the whole of the authorisation, and it is the right one: only somebody who has
   * proved they hold the Account can say they were told about it. The Account is the Session's own
   * rather than one a client named, so a patched client cannot dismiss somebody else's notice.
   *
   * <p>Nothing to acknowledge is answered with {@link Ok} rather than refused. What the caller asked
   * for is that the notice be over, and afterwards it is — and a client that sent this twice, or on
   * a Session that never had one, has not done anything wrong.
   */
  private Response acknowledgePasswordReset(
      AcknowledgePasswordReset request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> {
          enrolments.declaredTo(live.accountName());
          return new Ok();
        });
  }

  /**
   * Answers with the secret, and records that one was issued and never what it was. Two things
   * happened where an Account was created or reset, and this is the half they have in common.
   */
  private Response issued(Enrolments.Issued issued, String accountName) {
    record(AuthenticationEventType.ENROLMENT_SECRET_ISSUED, accountName);
    // The one place the secret is turned into text, on its way out of this process and onto the
    // screen it is read off. Nothing keeps what this returns.
    return new EnrolmentIssued(issued.secret().text(), issued.expiresAt());
  }

  /**
   * The SecretVault this Account's password opens, or empty where it opens none.
   *
   * <p>An Administrator is not asked about: the DataKey is never wrapped for one, so a derivation
   * would be a hundred milliseconds spent proving something the file already says. An Operator whose
   * Account holds no wrap — provisioned before this Vault existed, or reset and not yet enrolled
   * again — comes back empty too, and is admitted to a feature that cannot read a secret until they
   * enrol.
   */
  private Optional<UnlockedVault> theVaultOf(Account account, char[] password) {
    if (account.role() != Role.OPERATOR) {
      return Optional.empty();
    }
    return vault.unlockFor(account.name(), password);
  }

  /**
   * Hands over one named secret, decrypted for this request and no longer.
   *
   * <p>Story 55 for a ProtectedFeature and story 58 for everybody: what is decrypted is what was
   * asked for, when it was asked for. There is no request here that reads the Vault as a whole, and
   * none that reads the DataKey, because the type that holds it is not one this module exports.
   */
  private Response readSecret(ReadSecret request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnOperator(live, () -> reveal(live, request.name())));
  }

  private Response reveal(SessionOutcome.Live live, String name) {
    return withTheVaultOf(
        live,
        unlocked ->
            unlocked
                .secretNamed(name)
                .<Response>map(SecretRevealed::new)
                .orElseGet(() -> new ErrorResponse(ErrorCode.NO_SUCH_SECRET)));
  }

  /** Puts a secret into the Vault, which is the other half of a Vault a ProtectedFeature can use. */
  private Response keepSecret(KeepSecret request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live ->
            onlyAnOperator(
                live,
                () ->
                    withTheVaultOf(
                        live,
                        unlocked -> {
                          unlocked.keep(request.name(), request.secret());
                          return new Ok();
                        })));
  }

  /**
   * Changes the password of the Session's own Account, and rewraps its copy of the DataKey with it.
   *
   * <p>Story 63: rotating a password must not cost the secrets. The rewrap goes through the key this
   * Session already holds, so it cannot hand access to somebody who did not have it, and it happens
   * before the new hash is recorded — an Account whose password changed and whose wrap did not would
   * be an Operator who can log in and read nothing.
   *
   * <p>The current password is verified again even though a Session is live, and a wrong one is
   * counted like any other failure. A Session left open on an unattended machine is exactly why ASVS
   * asks for the password at this moment, and an uncounted guess here would make this the one place
   * in the system where guessing is free.
   */
  private Response changeOwnPassword(ChangeOwnPassword request, ConnectionHandle connection) {
    return onTheSessionNamedBy(request.token(), connection, live -> changePasswordFor(live, request));
  }

  private Response changePasswordFor(SessionOutcome.Live live, ChangeOwnPassword request) {
    Optional<Account> account = store.findByName(live.accountName());
    if (account.isEmpty()) {
      // The Account behind a live Session is gone. It cannot be this Session's own doing — an
      // Administrator cannot delete themselves — so it is a store that changed under the service.
      return new ErrorResponse(ErrorCode.NO_SUCH_ACCOUNT);
    }
    boolean verified = verified(account.get(), request.currentPassword());

    // Read after the verification rather than instead of it, exactly as authenticate does it and
    // for the same reason: a refusal costs the same whether the Account is locked or wrong.
    Optional<Duration> refusedFor = lockouts.refusalOf(live.accountName());
    if (refusedFor.isPresent()) {
      record(AuthenticationEventType.AUTHENTICATION_REFUSED_LOCKED_OUT, live.accountName());
      return Denied.lockedFor(refusedFor.get());
    }
    if (!verified) {
      return refuse(account.get(), AuthenticationEventType.AUTHENTICATION_FAILED_WRONG_PASSWORD);
    }
    Assessment assessment = policy.assessPassword(request.newPassword());
    if (!assessment.violations().isEmpty()) {
      return new PolicyRefused(assessment.violations());
    }
    rewrapAndRecord(live, account.get(), request.newPassword(), assessment);
    lockouts.succeeded(live.accountName());
    record(AuthenticationEventType.PASSWORD_CHANGED, live.accountName());
    return new Ok();
  }

  /**
   * Writes the new password and rewraps the DataKey under it, or leaves the Account exactly as it
   * was.
   *
   * <p>Two files have to agree and nothing spans both, so this is the closest thing to a transaction
   * available: the store goes first, and a Vault that will not take the rewrap puts the old hash
   * back. Either order without that would end the same way — a password that authenticates and a
   * wrapped key it does not open, an Operator who can log in and read nothing, with no sign of it
   * until they ask for a secret.
   *
   * <p>An Administrator holds no wrapped copy, so for them this is the store write and nothing else.
   *
   * @throws VaultException if the rewrap failed, once the old password has been put back
   */
  private void rewrapAndRecord(
      SessionOutcome.Live live, Account account, char[] newPassword, Assessment assessment) {
    store.recordChosenPassword(
        account.name(), authenticator.hash(newPassword), assessment.strength());
    try {
      live.vault().ifPresent(unlocked -> unlocked.rewrapUnder(newPassword));
    } catch (VaultException e) {
      account
          .passwordHash()
          .ifPresent(
              hash -> store.recordChosenPassword(account.name(), hash, account.passwordStrength()));
      throw e;
    }
  }

  /**
   * Whether that password is this Account's, spending the same Argon2id work either way.
   *
   * <p>An Account awaiting enrolment has no hash to be right about, so it costs a verification
   * against a password nobody holds and is refused. Shared by the login screen and by a password
   * being changed, so that neither can drift into being the cheaper of the two.
   */
  private boolean verified(Account account, char[] password) {
    return account
        .passwordHash()
        .map(hash -> authenticator.verify(password, hash))
        .orElseGet(() -> authenticator.verifyAgainstAbsentAccount(password));
  }

  /**
   * Deletes an Operator, and their wrapped copy of the DataKey with them.
   *
   * <p>Story 62, and the order is the point: the wrap goes first, so that a delete which fails
   * halfway leaves an Account with no Vault access rather than Vault access with no Account. The
   * second of those would be reachable again by creating a name.
   */
  private Response deleteAccount(DeleteAccount request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnAdministrator(live, () -> deleteFor(request.accountName())));
  }

  private Response deleteFor(String accountName) {
    Optional<Account> account = store.findByName(accountName);
    if (account.isEmpty()) {
      return new ErrorResponse(ErrorCode.NO_SUCH_ACCOUNT);
    }
    if (account.get().role() == Role.ADMINISTRATOR) {
      return new ErrorResponse(ErrorCode.CANNOT_DELETE_THE_ADMINISTRATOR);
    }
    // Nothing needs to end the deleted Operator's Session, because they cannot have one: a machine
    // holds at most one Session at a time and this request came from the Administrator's.
    vault.destroyWrapFor(accountName);
    store.delete(accountName);
    record(AuthenticationEventType.ACCOUNT_DELETED, accountName);
    return new Ok();
  }

  /**
   * Lists every Account, which is what the administration panel is drawn from.
   *
   * <p>The only request that reads the CredentialStore as a whole, and the only one whose answer is
   * about Accounts other than the one asking — so it is worth being plain about what it hands over.
   * A name, the Role, the coarse band, the language preference and the Lockout: nothing a password
   * could be recovered from, because {@link AccountSummary} has no field one could travel in.
   *
   * <p>Reading the list is not recorded. The record is of things that happened to Accounts — an
   * Account changed, a configuration changed, an export made — and an Administrator looking at the
   * screen they administer the deployment from has changed nothing. What they go on to do from it
   * is recorded where it is done.
   */
  private Response listAccounts(ListAccounts request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(), connection, live -> onlyAnAdministrator(live, this::everyAccount));
  }

  /**
   * Every Account, with the same arithmetic behind each Lockout as the login screen's refusals use.
   *
   * <p>Asking {@link Lockouts} rather than reading the moment out of the store is what keeps the
   * panel and the login screen from disagreeing about who is locked out: a Lockout that has run out
   * is forgotten as it is read, here exactly as it is there, so an Administrator is never shown a
   * refusal that the next attempt would not meet.
   */
  private Response everyAccount() {
    return new AccountsListed(
        store.accounts().stream()
            .map(account -> account.lockedFor(lockouts.refusalOf(account.name())))
            .toList());
  }

  /**
   * Answers a request only an Operator may make, or refuses it and writes down that it happened.
   *
   * <p>This is where ADR-0005 is enforced, and it is enforced here rather than in the client for the
   * reason that decision turns on: the refusal is not a boundary and is not claimed to be one. An
   * Administrator who wants a secret creates an Operator and enrols it, which works. What this buys
   * is that the direct route leaves a line in a record they cannot edit, and the route that works
   * leaves two more.
   */
  private Response onlyAnOperator(
      SessionOutcome.Live live, Supplier<Response> whenItIsAnOperators) {
    if (live.role() != Role.OPERATOR) {
      record(AuthenticationEventType.VAULT_REFUSED_TO_AN_ADMINISTRATOR, live.accountName());
      return new ErrorResponse(ErrorCode.NOT_AN_OPERATOR);
    }
    return whenItIsAnOperators.get();
  }

  /**
   * Answers a request that needs this Session's Vault, or says that this Session holds none.
   *
   * <p>Holding none is not a refusal of anything: it is an Account with no wrapped copy of the
   * DataKey, which is what an Operator provisioned before the Vault existed looks like, and what one
   * whose password has just been taken away looks like until they enrol again. The remedy is an
   * enrolment, so the caller is told that rather than being told the Vault is broken.
   */
  private static Response withTheVaultOf(
      SessionOutcome.Live live, Function<UnlockedVault, Response> then) {
    return live.vault()
        .map(then)
        .orElseGet(() -> new ErrorResponse(ErrorCode.NO_VAULT_ACCESS));
  }

  /**
   * Refuses an attempt against an Account that exists, records why, and remembers that it happened.
   *
   * <p>The failure that reaches the configured number is answered as the Lockout it has just
   * caused rather than as one more wrong password: someone told only that it failed keeps guessing
   * at an Account that has stopped listening, which is the whole of story 43. It is two events and
   * not one — the attempt, and the Lockout it caused — because they are two things that happened.
   */
  private Response refuse(Account account, AuthenticationEventType why) {
    record(why, account.name());
    Optional<Duration> lockedFor = lockouts.failed(account.name());
    if (lockedFor.isEmpty()) {
      return Denied.because(DeniedReason.AUTH_FAILED);
    }
    record(AuthenticationEventType.ACCOUNT_LOCKED_OUT, account.name());
    return Denied.lockedFor(lockedFor.get());
  }

  /** Whether a Session is live, once one that has run out has been ended. */
  private boolean theMachineIsBusy() {
    expireAnySessionThatIsDue(store.inactivityPeriod());
    return sessions.anyLive();
  }

  /** The Operator did something: the countdown starts again. */
  private Response reportActivity(ReportActivity request, ConnectionHandle connection) {
    InactivityPeriod period = theConfiguredPeriod();
    return answerFor(sessions.reportActivity(request.token(), connection, period));
  }

  /** Asking is not activity: the countdown is read and left where it was. */
  private Response askIfSessionIsLive(AskIfSessionIsLive request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(), connection, live -> new SessionLive(live.expiresIn()));
  }

  /**
   * Ends a Session because the Operator said so. A Session that had already ended is reported as
   * such rather than answered with a cheerful {@link Ok}: the person is owed the difference between
   * having logged out and having been logged out.
   */
  private Response logOut(Logout request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> {
          sessions.end(request.token(), connection);
          return new Ok();
        });
  }

  /**
   * Changes how long a Session may idle, for this deployment and from now on.
   *
   * <p>The Role checked is the one the Session was granted in, which the service decided when it
   * verified a password. A client asking on behalf of a Session that is not an Administrator's is
   * refused here, in the privileged process, and a patched one is refused identically.
   */
  private Response changeInactivityPeriod(
      ChangeInactivityPeriod request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnAdministrator(live, () -> changeFor(live, request.period())));
  }

  /**
   * Forgets what an Account has failed, which is how the Administrator releases a colleague who
   * fat-fingered their password.
   *
   * <p>A name no Account holds is said plainly rather than answered with a cheerful {@link Ok}: an
   * Administrator who mistyped it would otherwise walk away believing the colleague is free, and
   * the colleague would stay locked out. Nothing is revealed by saying so — the Session asking is
   * one the service granted in the Role that manages Accounts.
   */
  private Response clearLockout(ClearLockout request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnAdministrator(live, () -> clearFor(request.accountName())));
  }

  /**
   * Copies the record out for an Administrator to read with their own tools, which is the only way
   * it is ever read (story 75).
   *
   * <p>The export is recorded after the copy is made rather than before, so the copy does not claim
   * to hold the export that produced it. What the exported file does not say about itself, the file
   * it was copied from says at the next export.
   */
  private Response exportAuthenticationEvents(
      ExportAuthenticationEvents request, ConnectionHandle connection) {
    return onTheSessionNamedBy(
        request.token(),
        connection,
        live -> onlyAnAdministrator(live, () -> exportTo(request.destination(), live)));
  }

  private Response exportTo(Path destination, SessionOutcome.Live live) {
    if (!isSomewhereThisServiceMayWrite(destination)) {
      return new ErrorResponse(ErrorCode.EXPORT_DESTINATION_REFUSED);
    }
    try {
      AuthenticationEventExport export = archive.exportTo(destination);
      record(AuthenticationEventType.AUTHENTICATION_EVENTS_EXPORTED, live.accountName());
      return new AuthenticationEventsExported(export);
    } catch (FileAlreadyExistsException e) {
      return new ErrorResponse(ErrorCode.EXPORT_DESTINATION_REFUSED);
    } catch (IOException e) {
      return new ErrorResponse(ErrorCode.EXPORT_FAILED);
    }
  }

  /**
   * Whether this service will write a copy of the record where it was asked to.
   *
   * <p>Three refusals, and each of them is about the process being privileged rather than about the
   * Administrator being untrusted. A relative path would be resolved against a working directory
   * the person asking cannot see. A path inside this service's own directory would let an export
   * land on the store, the record or the key it is chained under. And a directory that does not
   * exist would have this process creating directories somewhere a client named.
   *
   * <p>Whether something is already there is not decided here — it is decided by the operating
   * system when the file is created, because a check made first and acted on afterwards is a check
   * a symbolic link planted in between goes round.
   */
  private boolean isSomewhereThisServiceMayWrite(Path destination) {
    Path absolute = destination.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    return destination.isAbsolute()
        && parent != null
        && Files.isDirectory(parent)
        && !absolute.startsWith(ownDirectory);
  }

  private Response clearFor(String accountName) {
    if (!lockouts.clear(accountName)) {
      return new ErrorResponse(ErrorCode.NO_SUCH_ACCOUNT);
    }
    record(AuthenticationEventType.LOCKOUT_CLEARED, accountName);
    return new Ok();
  }

  /**
   * Answers a request only an Administrator may make, or refuses it.
   *
   * <p>The Role checked is the one the Session was granted in, which the service decided when it
   * verified a password. A client asking on behalf of a Session that is not an Administrator's is
   * refused here, in the privileged process, and a patched one is refused identically.
   */
  private static Response onlyAnAdministrator(
      SessionOutcome.Live live, Supplier<Response> whenItIsTheAdministrators) {
    if (live.role() != Role.ADMINISTRATOR) {
      return new ErrorResponse(ErrorCode.NOT_ADMINISTRATOR);
    }
    return whenItIsTheAdministrators.get();
  }

  /**
   * Answers a request that only a live Session may make, or says why there is not one.
   *
   * <p>Every such request is the same three steps — expire whatever the clocks have finished with,
   * look the token up, and refuse it if what comes back is not a Session — and only the fourth step
   * differs. Asking is not activity, so none of them touches the countdown.
   */
  private Response onTheSessionNamedBy(
      SessionToken token,
      ConnectionHandle connection,
      Function<SessionOutcome.Live, Response> then) {
    InactivityPeriod period = theConfiguredPeriod();
    return answerFor(sessions.statusOf(token, connection, period), then);
  }

  private Response changeFor(SessionOutcome.Live live, InactivityPeriod period) {
    store.setInactivityPeriod(period);
    record(AuthenticationEventType.CONFIGURATION_CHANGED, live.accountName());
    return new Ok();
  }

  /**
   * How long a Session may idle here, with any Session the clocks have finished with already ended.
   *
   * <p>Every request that touches a Session starts here, which is what makes expiry the service's
   * decision rather than a client's. The period is read from the store each time rather than
   * remembered, so that an Administrator changing it changes what happens next.
   */
  private InactivityPeriod theConfiguredPeriod() {
    InactivityPeriod period = store.inactivityPeriod();
    expireAnySessionThatIsDue(period);
    return period;
  }

  private void expireAnySessionThatIsDue(InactivityPeriod period) {
    sessions.expireIfDue(period).ifPresent(this::recordIfTheClockJumped);
  }

  /**
   * A Session ending because someone walked away is ordinary and is not recorded. One ending
   * because the machine's clock stopped agreeing with the clock that cannot be moved is not
   * ordinary, and story 53 asks for it to be neither useful nor invisible.
   */
  private void recordIfTheClockJumped(ExpiredSession expired) {
    if (expired.reason() == SessionEndedReason.CLOCK_JUMPED) {
      record(AuthenticationEventType.SESSION_ENDED_BY_A_CLOCK_JUMP, expired.accountName());
    }
  }

  private void record(AuthenticationEventType type, String subject) {
    events.record(new AuthenticationEvent(clock.wallTime(), type, subject));
  }

  private static Response answerFor(SessionOutcome outcome) {
    return answerFor(outcome, live -> new SessionLive(live.expiresIn()));
  }

  private static Response answerFor(
      SessionOutcome outcome, Function<SessionOutcome.Live, Response> whenItIsLive) {
    return switch (outcome) {
      case SessionOutcome.Live live -> whenItIsLive.apply(live);
      case SessionOutcome.Ended ended -> new SessionEnded(ended.reason());
    };
  }

  /** Synchronised with {@link #handle}, so that shutting down waits for the answer in flight. */
  @Override
  public synchronized void close() {
    try {
      vault.close();
    } finally {
      store.close();
    }
  }
}
