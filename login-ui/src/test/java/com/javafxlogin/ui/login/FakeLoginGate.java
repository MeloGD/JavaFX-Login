package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventExport;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seam 3's stand-in for the AuthenticationService: a gate that admits whoever it was told to, and
 * answers the first-run wizard however it was told to.
 *
 * <p>No service, no socket and no crypto. That is the point — a window that fails to close, or a
 * refusal that says too much, is then a failure of the window and cannot arrive dressed up as a
 * failure of the hashing.
 *
 * <p>It is asked from whichever thread the window makes its attempt on, so what it records is kept
 * thread-safe.
 */
final class FakeLoginGate implements LoginGate {

  private final Map<String, String> admissible = new ConcurrentHashMap<>();
  private final List<String> attempts = new CopyOnWriteArrayList<>();
  private final List<String> creations = new CopyOnWriteArrayList<>();

  private final AtomicInteger activityReports = new AtomicInteger();
  private final AtomicInteger questionsAboutTheSession = new AtomicInteger();
  private final AtomicInteger logouts = new AtomicInteger();
  private final AtomicInteger noticesRead = new AtomicInteger();

  private final List<String> enrolments = new CopyOnWriteArrayList<>();

  /** The SecretVault, as far as a window ever needs one: a map with no key anywhere near it. */
  private final Map<String, String> secrets = new ConcurrentHashMap<>();

  /** Whoever is added here administers the deployment with this password, and nobody else does. */
  private final Map<String, String> administrators = new ConcurrentHashMap<>();

  /** The CredentialStore, as far as the administration panel ever sees one: a list of summaries. */
  private final Map<String, AccountSummary> accounts = new ConcurrentHashMap<>();

  /** What the panel asked for, in the order it asked, so that a test can assert on the request. */
  private final List<String> administrations = new CopyOnWriteArrayList<>();

  private final AtomicInteger secretsIssued = new AtomicInteger();

  private volatile InactivityPeriod configuredPeriod;
  private volatile Path exportedTo;
  private volatile AuthenticationEventExport nextExport =
      new AuthenticationEventExport(12, true);

  /** What the service refuses the next administration request with, or null where it obliges. */
  private volatile AdministrationRefusedReason administrationRefused;

  /** What the service refuses the next Account creation with, where the name breaks a rule. */
  private volatile List<PolicyViolation> nameRefusedFor;

  private volatile boolean reachable = true;
  private volatile boolean firstRunNeeded;
  private volatile boolean aSessionIsAlreadyLive;
  private volatile Duration lockedFor;
  private volatile FirstRunOutcome nextOutcome = new AdministratorCreated();
  private volatile EnrolmentOutcome nextEnrolmentOutcome = new Enrolled();
  private volatile Instant passwordResetAt;
  private volatile boolean noticeReadFails;

  /** What the Vault answers with instead of a secret, or null where it answers with the secret. */
  private volatile SecretWithheldReason vaultWithholds;

  /** An Account the service says has no password yet, whatever is typed for it. */
  private volatile String awaitingEnrolment;

  /** What the service says about a Session from now on, until a test says otherwise. */
  private volatile SessionStatus status = new SessionContinues(Optional.of(Duration.ofMinutes(15)));

  /** Whoever is added here is admitted with this password, and refused with any other. */
  FakeLoginGate admitting(String accountName, String password) {
    admissible.put(accountName, password);
    return this;
  }

  /** A machine with no Administrator, which is what puts the wizard on the screen. */
  FakeLoginGate needingItsAdministrator() {
    firstRunNeeded = true;
    return this;
  }

  /** What the service answers the next attempt to create the Administrator with. */
  void answerTheWizardWith(FirstRunOutcome outcome) {
    nextOutcome = outcome;
  }

  /** A machine someone else is already working on, which is the one refusal that says so. */
  FakeLoginGate withASessionAlreadyLive() {
    aSessionIsAlreadyLive = true;
    return this;
  }

  /** An Account the service says has failed too often, and how long it has left to wait. */
  FakeLoginGate withAnAccountLockedFor(Duration remaining) {
    lockedFor = remaining;
    return this;
  }

  /** An Account the service says is waiting for somebody to give it a password. */
  FakeLoginGate awaitingEnrolment(String accountName) {
    awaitingEnrolment = accountName;
    return this;
  }

  /** What the service answers the next enrolment with. */
  void answerTheEnrolmentWith(EnrolmentOutcome outcome) {
    nextEnrolmentOutcome = outcome;
  }

  /** What the service says on the next admission about a reset the person was never told about. */
  void withAPasswordResetAt(Instant resetAt) {
    passwordResetAt = resetAt;
  }

  /**
   * A service that answers everything else and cannot be told the notice was read. Narrower than
   * {@link #becomeUnreachable} on purpose: a service that has gone away entirely also ends the
   * Session the guard is watching, which would close the window a test about the notice is looking
   * at.
   */
  void cannotBeToldTheNoticeWasRead() {
    noticeReadFails = true;
  }

  /** A secret this deployment's ProtectedFeature will find in the Vault. */
  FakeLoginGate holdingTheSecret(String name, String secret) {
    secrets.put(name, secret);
    return this;
  }

  /** A Session that holds no wrapped copy of the DataKey, or one the Vault is not an Operator's. */
  FakeLoginGate withTheVaultWithholding(SecretWithheldReason reason) {
    vaultWithholds = reason;
    return this;
  }

  /** What the enrolment screen offered, as {@code name/secret/password}, in order. */
  List<String> enrolments() {
    return List.copyOf(enrolments);
  }

  /** Makes every later attempt fail the way an AuthenticationService that is not there fails. */
  void becomeUnreachable() {
    reachable = false;
  }

  /** How long the service says a Session has left, every time it is asked. */
  void sessionsLastFor(Duration remaining) {
    status = new SessionContinues(Optional.of(remaining));
  }

  /** A kiosk: the service says there is nothing to count down to. */
  void sessionsNeverExpire() {
    status = new SessionContinues(Optional.empty());
  }

  /** From the next question onwards, the service says the Session is over. */
  void theSessionEnds(SessionEndedReason reason) {
    status = new SessionOver(reason);
  }

  /** How many times the SessionGuard has said the Operator did something. */
  int activityReports() {
    return activityReports.get();
  }

  /** How many times the guard has asked whether the Session it watches is still there. */
  int questionsAboutTheSession() {
    return questionsAboutTheSession.get();
  }

  /** How many times a Session was ended deliberately. */
  int logouts() {
    return logouts.get();
  }

  /** How many times somebody said they had read the notice about their password being reset. */
  int noticesRead() {
    return noticesRead.get();
  }

  /** What the window offered, as {@code name/password}, in the order it offered it. */
  List<String> attempts() {
    return List.copyOf(attempts);
  }

  /** An Account that administers this deployment, admitted at the panel with this password. */
  FakeLoginGate administeredBy(String accountName, String password) {
    administrators.put(accountName, password);
    return holding(
        new AccountSummary(
            accountName,
            Role.ADMINISTRATOR,
            PasswordStrength.STRONG,
            Optional.empty(),
            Optional.empty()));
  }

  /** An Account the service lists when the panel asks for the Accounts of this deployment. */
  FakeLoginGate holding(AccountSummary account) {
    accounts.put(account.name(), account);
    return this;
  }

  /** An ordinary Operator with nothing the matter with it. */
  FakeLoginGate holdingTheOperator(String accountName) {
    return holding(
        new AccountSummary(
            accountName,
            Role.OPERATOR,
            PasswordStrength.ACCEPTABLE,
            Optional.empty(),
            Optional.empty()));
  }

  /** What the service refuses every administration request with, until a test says otherwise. */
  void refuseAdministrationWith(AdministrationRefusedReason reason) {
    administrationRefused = reason;
  }

  /** What the AccountPolicy makes of the next name the panel offers it. */
  void refuseTheNameFor(List<PolicyViolation> violations) {
    nameRefusedFor = violations;
  }

  /** What the next export of the record comes to. */
  void exportsComeTo(AuthenticationEventExport export) {
    nextExport = export;
  }

  /** What the panel asked the service to do, in order, as {@code request:subject}. */
  List<String> administrations() {
    return List.copyOf(administrations);
  }

  /** The Accounts this fake service holds, as the panel would list them. */
  List<AccountSummary> accountsHeld() {
    return accounts.values().stream().sorted(Comparator.comparing(AccountSummary::name)).toList();
  }

  /** How long a Session may idle here, as the panel last configured it. */
  InactivityPeriod configuredPeriod() {
    return configuredPeriod;
  }

  /** Where the panel last asked for the record to be copied. */
  Path exportedTo() {
    return exportedTo;
  }

  @Override
  public Admission administer(String accountName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    attempts.add(accountName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (lockedFor != null) {
      return NotAdmitted.lockedFor(lockedFor);
    }
    if (!Objects.equals(administrators.get(accountName), offered)) {
      // As the service does: an Operator asking to administer is refused in the same words as a
      // wrong password, because telling the two apart would name the Role an Account holds.
      return NotAdmitted.because(DeniedReason.AUTH_FAILED);
    }
    return new Admitted(new Session(SessionToken.generate(new SecureRandom())));
  }

  @Override
  public AccountListing accounts(Session session) {
    Objects.requireNonNull(session, "session");
    administrations.add("accounts");
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    return new AccountsSeen(accountsHeld());
  }

  @Override
  public AccountProvisioned createOperator(Session session, String accountName) {
    Objects.requireNonNull(session, "session");
    administrations.add("createOperator:" + accountName);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    if (nameRefusedFor != null) {
      return new PolicyRefusal(nameRefusedFor);
    }
    if (accounts.containsKey(accountName)) {
      return new AdministrationRefused(AdministrationRefusedReason.ACCOUNT_EXISTS);
    }
    // As the real service does: the Account exists from here on, with no password and the weakest
    // band, until somebody turns the secret below into one.
    accounts.put(
        accountName,
        new AccountSummary(
            accountName,
            Role.OPERATOR,
            PasswordStrength.WEAK,
            Optional.empty(),
            Optional.empty()));
    return issueASecret();
  }

  @Override
  public AccountProvisioned resetThePasswordOf(Session session, String accountName) {
    Objects.requireNonNull(session, "session");
    administrations.add("resetThePasswordOf:" + accountName);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    AccountSummary account = accounts.get(accountName);
    if (account == null) {
      return new AdministrationRefused(AdministrationRefusedReason.NO_SUCH_ACCOUNT);
    }
    if (account.role() == Role.ADMINISTRATOR) {
      return new AdministrationRefused(AdministrationRefusedReason.CANNOT_ENROL_THE_ADMINISTRATOR);
    }
    return issueASecret();
  }

  /** A secret that is different every time, as one generated from 128 bits would be. */
  private AccountProvisioned issueASecret() {
    return new EnrolmentSecretIssued(
        "SECRET-%04d".formatted(secretsIssued.incrementAndGet()),
        Instant.parse("2026-03-04T09:00:00Z"));
  }

  @Override
  public AdministrationOutcome deleteOperator(Session session, String accountName) {
    Objects.requireNonNull(session, "session");
    administrations.add("deleteOperator:" + accountName);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    AccountSummary account = accounts.get(accountName);
    if (account == null) {
      return new AdministrationRefused(AdministrationRefusedReason.NO_SUCH_ACCOUNT);
    }
    if (account.role() == Role.ADMINISTRATOR) {
      return new AdministrationRefused(AdministrationRefusedReason.CANNOT_DELETE_THE_ADMINISTRATOR);
    }
    accounts.remove(accountName);
    return new Administered();
  }

  @Override
  public AdministrationOutcome clearTheLockoutOf(Session session, String accountName) {
    Objects.requireNonNull(session, "session");
    administrations.add("clearTheLockoutOf:" + accountName);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    AccountSummary account = accounts.get(accountName);
    if (account == null) {
      return new AdministrationRefused(AdministrationRefusedReason.NO_SUCH_ACCOUNT);
    }
    accounts.put(accountName, account.lockedFor(Optional.empty()));
    return new Administered();
  }

  @Override
  public AdministrationOutcome useInactivityPeriod(Session session, InactivityPeriod period) {
    Objects.requireNonNull(session, "session");
    administrations.add("useInactivityPeriod:" + period.text());
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    configuredPeriod = period;
    return new Administered();
  }

  @Override
  public ExportOutcome exportAuthenticationEventsTo(Session session, Path destination) {
    Objects.requireNonNull(session, "session");
    administrations.add("exportAuthenticationEventsTo:" + destination);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (administrationRefused != null) {
      return new AdministrationRefused(administrationRefused);
    }
    exportedTo = destination;
    return new EventsExported(nextExport);
  }

  /** What the wizard offered, as {@code name/password}, in the order it offered it. */
  List<String> creations() {
    return List.copyOf(creations);
  }

  @Override
  public Admission admit(String accountName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    attempts.add(accountName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (aSessionIsAlreadyLive) {
      return NotAdmitted.because(DeniedReason.SESSION_ALREADY_LIVE);
    }
    if (lockedFor != null) {
      return NotAdmitted.lockedFor(lockedFor);
    }
    if (accountName.equals(awaitingEnrolment)) {
      return NotAdmitted.because(DeniedReason.ENROLMENT_REQUIRED);
    }
    if (!Objects.equals(admissible.get(accountName), offered)) {
      return NotAdmitted.because(DeniedReason.AUTH_FAILED);
    }
    return new Admitted(
        new Session(SessionToken.generate(new SecureRandom())),
        Optional.ofNullable(passwordResetAt));
  }

  @Override
  public EnrolmentOutcome completeEnrolment(String accountName, char[] secret, char[] password) {
    // Copied at once: the window blanks both arrays it handed over as soon as this returns.
    String offeredSecret = new String(secret);
    String chosen = new String(password);
    enrolments.add(accountName + "/" + offeredSecret + "/" + chosen);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (nextEnrolmentOutcome instanceof Enrolled) {
      // As the real service does: the Account now has a password of its own, and the one thing it
      // was waiting for is over.
      admissible.put(accountName, chosen);
      awaitingEnrolment = null;
    }
    return nextEnrolmentOutcome;
  }

  @Override
  public void passwordResetNoticeWasRead(Session session) {
    Objects.requireNonNull(session, "session");
    noticesRead.incrementAndGet();
    if (!reachable || noticeReadFails) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    // As the real service does: it is over, and the next admission says nothing.
    passwordResetAt = null;
  }

  @Override
  public SessionStatus reportActivity(Session session) {
    Objects.requireNonNull(session, "session");
    activityReports.incrementAndGet();
    return answerAboutTheSession();
  }

  @Override
  public SessionStatus stillLive(Session session) {
    Objects.requireNonNull(session, "session");
    questionsAboutTheSession.incrementAndGet();
    return answerAboutTheSession();
  }

  @Override
  public void logOut(Session session) {
    Objects.requireNonNull(session, "session");
    logouts.incrementAndGet();
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    status = new SessionOver(SessionEndedReason.NO_SUCH_SESSION);
  }

  private SessionStatus answerAboutTheSession() {
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    return status;
  }

  @Override
  public SecretOutcome secretNamed(Session session, String name) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(name, "name");
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (vaultWithholds != null) {
      return new SecretWithheld(vaultWithholds);
    }
    String secret = secrets.get(name);
    return secret == null
        ? new SecretWithheld(SecretWithheldReason.NO_SUCH_SECRET)
        : new SecretGiven(secret.toCharArray());
  }

  @Override
  public SecretKeepingOutcome keepSecret(Session session, String name, char[] secret) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(name, "name");
    // Copied at once, as every other array handed to this fake is.
    String kept = new String(secret);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (vaultWithholds != null) {
      return new SecretWithheld(vaultWithholds);
    }
    secrets.put(name, kept);
    return new SecretKept();
  }

  @Override
  public boolean firstRunNeeded() {
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    return firstRunNeeded;
  }

  @Override
  public FirstRunOutcome createAdministrator(String administratorName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    creations.add(administratorName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (nextOutcome instanceof AdministratorCreated) {
      // As the real service does: from here on this installation has its Administrator and the
      // wizard is over. The Account is deliberately not made admissible — the login screen asks
      // to act as an Operator, and the service refuses the Administrator there.
      firstRunNeeded = false;
    }
    return nextOutcome;
  }
}
