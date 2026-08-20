package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.EnrolmentSecret;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.authentication.ServiceProcess;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.machine.MachineAdministrators;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import com.javafxlogin.core.store.CredentialStore;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gate a shipped product runs behind, against a real AuthenticationService over a real socket.
 *
 * <p>Seam 3 fakes this gate so that the windows can be tested without any of it. This is the one
 * place where the fake is checked against the thing it stands in for: that a Session really is
 * granted, that a refusal really comes back as a refusal, and — the part that matters most — that
 * the Administrator is turned away by the service rather than by anything in this process.
 *
 * <p>No JavaFX is involved and no display is needed. The gate's other half, which shows windows, is
 * {@link LoginWindowTest}'s subject.
 */
class ServiceLoginGateTest {

  /** Deliberately cheap: this suite is about who is admitted, not about what a hash costs. */
  private static final Argon2Parameters CHEAP = new Argon2Parameters(256, 1, 1, 32);

  /**
   * Who this machine's operating system administers it, decided here rather than read from the
   * developer's group memberships — and decided on the name the kernel attached to the connection,
   * so that what is admitted is exactly what the socket reported about this very process.
   */
  private static final MachineAdministrators THE_ACCOUNT_RUNNING_THIS_SUITE =
      peer -> System.getProperty("user.name").equals(peer.userName());

  private static final MachineAdministrators NOBODY = peer -> false;

  /** What V004 writes, which is what a store this suite creates is configured with. */
  private static final int FAILURES_THAT_LOCK = 5;

  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";

  /** Somebody who has been given a code and has never had a password. */
  private static final String NEWCOMER = "rosalind.sanders";

  private static final String NEWCOMER_PASSWORD = "A-Third-Horse-3";

  /** What a ProtectedFeature keeps in the SecretVault: a credential for a system it connects to. */
  private static final String A_SECRETS_NAME = "warehouse.database.password";

  private static final String A_SECRET = "sa/8Xk!connect";

  @TempDir Path runtimeDirectory;

  private Path socketPath;
  private Path storeFile;
  private ServiceProcess process;

  @BeforeEach
  void startTheService() throws IOException {
    socketPath = runtimeDirectory.resolve("authentication.sock");
    storeFile = runtimeDirectory.resolve("credentials.db");
    provisionOperator();
    process = start();
  }

  @AfterEach
  void stopTheService() {
    process.close();
  }

  @Test
  void admitsAnOperatorWithTheirOwnPassword() {
    Admission admission = gate().admit(OPERATOR, OPERATOR_PASSWORD.toCharArray());

    Admitted admitted = assertInstanceOf(Admitted.class, admission);
    assertEquals(16, admitted.session().token().copyOfBytes().length);
  }

  @Test
  void refusesAWrongPassword() {
    Admission admission = gate().admit(OPERATOR, "Wrong-Horse-9".toCharArray());

    assertEquals(NotAdmitted.because(DeniedReason.AUTH_FAILED), admission);
  }

  @Test
  void refusesAnAccountThatDoesNotExist() {
    Admission admission = gate().admit("nobody.here", OPERATOR_PASSWORD.toCharArray());

    assertEquals(NotAdmitted.because(DeniedReason.AUTH_FAILED), admission);
  }

  /**
   * The whole of a Session as the gate sees it, against the real service: activity reported, time
   * left read, and a logout that ends it. Seam 3's fake answers these; this is where what it stands
   * in for is checked.
   */
  @Test
  void carriesASessionThroughToTheService() {
    LoginGate gate = gate();
    Session session = assertInstanceOf(
            Admitted.class, gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()))
        .session();

    SessionContinues afterActivity =
        assertInstanceOf(SessionContinues.class, gate.reportActivity(session));
    assertEquals(Optional.of(Duration.ofMinutes(15)), afterActivity.expiresIn());
    assertInstanceOf(SessionContinues.class, gate.stillLive(session));

    gate.logOut(session);

    assertEquals(
        new SessionOver(SessionEndedReason.NO_SUCH_SESSION), gate.stillLive(session));
  }

  /**
   * Stories 40 and 43 across the socket: the refusal that locks an Account comes back saying so,
   * and saying how long — the number the window puts in front of a person is the service's own.
   */
  @Test
  void carriesALockoutBackWithTheWaitTheServiceDecided() {
    LoginGate gate = gate();
    for (int attempt = 1; attempt < FAILURES_THAT_LOCK; attempt++) {
      assertEquals(
          NotAdmitted.because(DeniedReason.AUTH_FAILED),
          gate.admit(OPERATOR, "Wrong-Horse-9".toCharArray()),
          "attempt " + attempt + " should have been an ordinary refusal");
    }

    Admission admission = gate.admit(OPERATOR, "Wrong-Horse-9".toCharArray());

    assertEquals(NotAdmitted.lockedFor(Duration.ofMinutes(15)), admission);
  }

  /** Story 54, through the gate a shipped product runs behind: the machine holds one Session. */
  @Test
  void refusesASecondSessionAndSaysWhichRefusalItIs() {
    assertInstanceOf(Admitted.class, gate().admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));

    Admission admission = gate().admit(OPERATOR, OPERATOR_PASSWORD.toCharArray());

    assertEquals(NotAdmitted.because(DeniedReason.SESSION_ALREADY_LIVE), admission);
  }

  /**
   * Stories 38 and 39: the Administrator does not reach the ProtectedFeature, and it is the service
   * that says so. This gate asks to act as an Operator and is told no with the Administrator's own
   * correct password — a client patched to ignore the answer would still hold no Session.
   */
  @Test
  void refusesTheAdministratorEvenWithTheRightPassword() {
    createTheAdministrator();

    Admission admission = gate().admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray());

    assertEquals(
        NotAdmitted.because(DeniedReason.AUTH_FAILED),
        admission,
        "the Administrator should not reach the ProtectedFeature");
  }

  /** Not being able to ask is not a refusal, and the person must not be sent to retype anything. */
  @Test
  void saysSoWhenThereIsNoServiceToAsk() {
    LoginGate gate = LoginGate.toService(runtimeDirectory.resolve("nothing-listens-here.sock"));

    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));
  }

  /**
   * A Session is bound to its connection, so the gate keeps one. When the service goes away that
   * connection dies with it: the attempt in flight fails as unreachable, and the next one opens a
   * fresh connection rather than the gate being useless until the application restarts.
   */
  @Test
  void reconnectsAfterTheServiceHasBeenRestarted() throws IOException {
    LoginGate gate = gate();
    assertInstanceOf(Admitted.class, gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));

    process.close();
    process = start();

    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));
    assertInstanceOf(
        Admitted.class,
        gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()),
        "the next attempt should have connected again");
  }

  private LoginGate gate() {
    return LoginGate.toService(socketPath);
  }

  private ServiceProcess start() throws IOException {
    return start(THE_ACCOUNT_RUNNING_THIS_SUITE);
  }

  private ServiceProcess start(MachineAdministrators administrators) throws IOException {
    return ServiceProcess.start(
        new BoundListeningChannelSource(socketPath), storeFile, CHEAP, administrators);
  }

  /**
   * An Operator with a password already, written straight into the store: this suite is about what
   * the gate carries, and reaching that state through the service would be an Administrator Session
   * and two more requests in every test. What enrolment does over the wire is asserted below.
   */
  private void provisionOperator() {
    String hash = new Authenticator(CHEAP).hash(OPERATOR_PASSWORD.toCharArray());
    try (CredentialStore store = CredentialStore.openOrCreate(storeFile)) {
      store.insert(new Account(OPERATOR, Role.OPERATOR, hash, PasswordStrength.ACCEPTABLE));
    }
  }

  /** Over the wire, the way the first-run wizard does it: no privilege this client lacks. */
  private void createTheAdministrator() {
    assertInstanceOf(
        AdministratorCreated.class,
        gate().createAdministrator(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray()));
  }

  /**
   * Issue #13 end to end, across the socket: an Administrator records which language somebody reads
   * and that Operator's next admission carries it. Nothing before the admission does — the login
   * screen has a name somebody typed, and a name is not an Account.
   */
  @Test
  void carriesALanguagePreferenceThroughToTheServiceAndBackOnTheNextAdmission() {
    createTheAdministrator();
    LoginGate gate = gate();
    Admitted admitted =
        assertInstanceOf(
            Admitted.class, gate.administer(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray()));
    assertEquals(
        Optional.empty(),
        admitted.languagePreference(),
        "nobody has said which language anybody reads yet");

    assertInstanceOf(
        Administered.class,
        gate.useLanguagePreference(
            admitted.session(), OPERATOR, Optional.of(Locale.forLanguageTag("es"))));
    gate.logOut(admitted.session());

    Admitted operator =
        assertInstanceOf(Admitted.class, gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));
    assertEquals(Optional.of(Locale.forLanguageTag("es")), operator.languagePreference());
  }

  /** A name no Account holds is refused rather than quietly accepted, as every other one is. */
  @Test
  void refusesALanguagePreferenceForANameNoAccountHolds() {
    createTheAdministrator();
    LoginGate gate = gate();
    Admitted admitted =
        assertInstanceOf(
            Admitted.class, gate.administer(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray()));

    AdministrationOutcome outcome =
        gate.useLanguagePreference(
            admitted.session(), "nobody.here", Optional.of(Locale.forLanguageTag("es")));

    assertEquals(
        new AdministrationRefused(AdministrationRefusedReason.NO_SUCH_ACCOUNT), outcome);
  }

  // --- the first run ---------------------------------------------------------------------

  @Test
  void saysTheBootstrapIsNeededUntilTheAdministratorExists() {
    LoginGate gate = gate();
    assertTrue(gate.firstRunNeeded(), "a store with no Administrator needs the wizard");

    gate.createAdministrator(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray());

    assertFalse(gate.firstRunNeeded(), "the wizard is over once the Administrator exists");
  }

  /**
   * The whole of the ticket's last criterion, end to end: what the wizard creates is an Account the
   * AuthenticationService authenticates. It is asked for as an Administrator rather than through
   * this gate, which admits Operators — the administration screen that will ask is its own ticket.
   */
  @Test
  void theAdministratorTheWizardCreatesCanAuthenticate() throws IOException {
    createTheAdministrator();

    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response response =
          client.send(
              new Authenticate(
                  ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray(), Role.ADMINISTRATOR));

      assertInstanceOf(Granted.class, response);
    }
  }

  /**
   * Nothing is handed back for the person to keep: no recovery key, no backup code, no backdoor.
   * The outcome is empty and equal to every other one, so a later ticket that put something in it
   * would fail here rather than ship.
   */
  @Test
  void issuesNothingAlongsideTheAdministratorItCreated() {
    FirstRunOutcome outcome =
        gate().createAdministrator(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray());

    assertEquals(new AdministratorCreated(), outcome);
  }

  /** The guard that keeps a normal user from claiming the Administrator on a fresh install. */
  @Test
  void refusesTheWizardToAPeerThatDoesNotAdministerTheMachine() throws IOException {
    process.close();
    process = start(NOBODY);

    FirstRunOutcome outcome =
        gate().createAdministrator(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray());

    assertEquals(new FirstRunRefused(FirstRunRefusedReason.NOT_MACHINE_ADMINISTRATOR), outcome);
  }

  /** A peer refused the wizard is still told which window to open. */
  @Test
  void stillSaysWhetherTheBootstrapIsNeededToAPeerThatMayNotRunIt() throws IOException {
    process.close();
    process = start(NOBODY);

    assertTrue(gate().firstRunNeeded());
  }

  @Test
  void refusesASecondAdministrator() {
    createTheAdministrator();

    FirstRunOutcome outcome =
        gate().createAdministrator("finch.upton", "Another-Horse-3".toCharArray());

    assertEquals(new FirstRunRefused(FirstRunRefusedReason.ADMINISTRATOR_EXISTS), outcome);
  }

  /**
   * The policy is enforced in the privileged process, so a wizard patched to skip it gets the same
   * answer. What comes back names every rule broken, which is what the window turns into sentences.
   */
  @Test
  void carriesBackEveryRuleThePolicyRefusedTheNameAndPasswordFor() {
    FirstRunOutcome outcome = gate().createAdministrator("admin", "short".toCharArray());

    PolicyRefusal refusal = assertInstanceOf(PolicyRefusal.class, outcome);
    assertTrue(
        refusal.violations().contains(PolicyViolation.ACCOUNT_NAME_BLOCKED),
        () -> "the blocked name was allowed through: " + refusal.violations());
    assertTrue(
        refusal.violations().contains(PolicyViolation.PASSWORD_TOO_SHORT),
        () -> "the short password was allowed through: " + refusal.violations());
  }

  @Test
  void createsNoAdministratorWhenThePolicyRefused() {
    gate().createAdministrator("admin", "short".toCharArray());

    assertTrue(gate().firstRunNeeded(), "a refused attempt created an Administrator anyway");
  }

  @Test
  void saysSoWhenThereIsNoServiceToAskAboutTheFirstRun() {
    LoginGate gate = LoginGate.toService(runtimeDirectory.resolve("nothing-listens-here.sock"));

    assertThrows(ServiceUnreachableException.class, gate::firstRunNeeded);
    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.createAdministrator(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray()));
  }

  // --- enrolment ------------------------------------------------------------------------------

  /**
   * The whole of what a person does at the enrolment screen, against the real service: the Account
   * that has no password says so in its own words, the secret becomes a password, and that password
   * is the one that admits them. Seam 3's fake answers all three; this is where it is checked.
   */
  @Test
  void carriesAnEnrolmentThroughToTheService() {
    createTheAdministrator();
    String secret = issueASecretFor(NEWCOMER);
    LoginGate gate = gate();

    assertEquals(
        NotAdmitted.because(DeniedReason.ENROLMENT_REQUIRED),
        gate.admit(NEWCOMER, "Nothing-Yet-1".toCharArray()));
    assertInstanceOf(
        Enrolled.class,
        gate.completeEnrolment(NEWCOMER, secret.toCharArray(), NEWCOMER_PASSWORD.toCharArray()));

    assertInstanceOf(Admitted.class, gate.admit(NEWCOMER, NEWCOMER_PASSWORD.toCharArray()));
  }

  /** A secret that is not the one comes back as a refusal, and never as an exception. */
  @Test
  void refusesASecretThatIsNotTheOneItIssued() {
    createTheAdministrator();
    issueASecretFor(NEWCOMER);

    EnrolmentOutcome outcome =
        gate()
            .completeEnrolment(
                NEWCOMER,
                EnrolmentSecret.generate(new SecureRandom()).text().toCharArray(),
                NEWCOMER_PASSWORD.toCharArray());

    assertEquals(EnrolmentRefused.because(DeniedReason.AUTH_FAILED), outcome);
  }

  /** The rules are the service's here too, and every one that was broken comes back. */
  @Test
  void carriesBackEveryRuleThePolicyRefusedTheChosenPasswordFor() {
    createTheAdministrator();
    String secret = issueASecretFor(NEWCOMER);

    EnrolmentOutcome outcome =
        gate().completeEnrolment(NEWCOMER, secret.toCharArray(), "short".toCharArray());

    PolicyRefusal refusal = assertInstanceOf(PolicyRefusal.class, outcome);
    assertTrue(
        refusal.violations().contains(PolicyViolation.PASSWORD_TOO_SHORT),
        () -> "the short password was allowed through: " + refusal.violations());
  }

  @Test
  void saysSoWhenThereIsNoServiceToEnrolAgainst() {
    LoginGate gate = LoginGate.toService(runtimeDirectory.resolve("nothing-listens-here.sock"));

    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.completeEnrolment(NEWCOMER, "K7QF".toCharArray(), "x".toCharArray()));
  }

  // --- the SecretVault ------------------------------------------------------------------------

  /**
   * Criterion 1 of the Vault's ticket, through the interface a host product actually holds: an
   * Operator is enrolled the way a person is, and the ProtectedFeature behind the gate asks for a
   * named secret and receives it.
   */
  @Test
  void aProtectedFeatureKeepsASecretAndAsksForItByName() {
    createTheAdministrator();
    LoginGate gate = gate();
    Session session = enrol(gate, NEWCOMER, NEWCOMER_PASSWORD);

    assertEquals(
        new SecretKept(),
        gate.keepSecret(session, A_SECRETS_NAME, A_SECRET.toCharArray()));

    SecretGiven given = assertInstanceOf(SecretGiven.class, gate.secretNamed(session, A_SECRETS_NAME));
    assertArrayEquals(A_SECRET.toCharArray(), given.secret());
  }

  /** Nothing under that name is an outcome the product can act on, not an exception it must catch. */
  @Test
  void saysSoWhenNothingIsKeptUnderThatName() {
    createTheAdministrator();
    LoginGate gate = gate();
    Session session = enrol(gate, NEWCOMER, NEWCOMER_PASSWORD);

    assertEquals(
        new SecretWithheld(SecretWithheldReason.NO_SUCH_SECRET),
        gate.secretNamed(session, "nothing.is.kept.here"));
  }

  /**
   * An Operator written straight into the store holds no wrapped copy of the DataKey, which is
   * exactly the state of an Account that existed before the Vault did. They are admitted, and they
   * reach no secret — the upgrade path must not lock anybody out of the product.
   */
  @Test
  void anOperatorWithNoWrappedCopyIsAdmittedAndReachesNoSecret() {
    LoginGate gate = gate();
    Session session =
        assertInstanceOf(Admitted.class, gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()))
            .session();

    assertEquals(
        new SecretWithheld(SecretWithheldReason.NO_VAULT_ACCESS),
        gate.secretNamed(session, A_SECRETS_NAME));
  }

  /** A Session that has ended reaches no secret, and the product is told which of the two it is. */
  @Test
  void saysSoWhenTheSessionIsOver() {
    createTheAdministrator();
    LoginGate gate = gate();
    Session session = enrol(gate, NEWCOMER, NEWCOMER_PASSWORD);
    gate.logOut(session);

    assertEquals(
        new SecretWithheld(SecretWithheldReason.SESSION_OVER),
        gate.secretNamed(session, A_SECRETS_NAME));
  }

  /**
   * Enrols an Account the way a person does — a secret from the Administrator, a password chosen at
   * the enrolment screen — and logs in with it, which is what unwraps its copy of the DataKey.
   *
   * <p>The gate is passed in rather than made here, because a Session is bound to the connection it
   * was granted on and every gate opens one of its own. A test that admitted on one gate and asked
   * on another would be a test about two clients.
   */
  private Session enrol(LoginGate gate, String accountName, String password) {
    String secret = issueASecretFor(accountName);
    assertInstanceOf(
        Enrolled.class,
        gate.completeEnrolment(accountName, secret.toCharArray(), password.toCharArray()));
    return assertInstanceOf(Admitted.class, gate.admit(accountName, password.toCharArray()))
        .session();
  }

  /**
   * Creates an Account awaiting enrolment the way an Administrator will once there is a panel to do
   * it from: over the wire, from an Administrator Session, and with the machine handed back
   * afterwards so that the enrolment below is not refused for a Session somebody left open.
   */
  private String issueASecretFor(String accountName) {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response admitted =
          client.send(
              new Authenticate(
                  ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray(), Role.ADMINISTRATOR));
      SessionToken administrator = assertInstanceOf(Granted.class, admitted).token();
      Response issued =
          client.send(new CreateAccount(administrator, accountName, Role.OPERATOR));
      client.send(new Logout(administrator));
      return assertInstanceOf(EnrolmentIssued.class, issued).secret();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
