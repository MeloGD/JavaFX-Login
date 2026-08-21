package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.BackedUpAccount;
import com.javafxlogin.core.account.FailedAuthentications;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventType;
import com.javafxlogin.core.backup.Backup;
import com.javafxlogin.core.backup.BackupContents;
import com.javafxlogin.core.backup.BackupFile;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AccountsListed;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.BackupExported;
import com.javafxlogin.core.ipc.BackupImported;
import com.javafxlogin.core.ipc.ChangeInactivityPeriod;
import com.javafxlogin.core.ipc.ChangeLanguagePreference;
import com.javafxlogin.core.ipc.CompleteEnrolment;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.ExportBackup;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.ImportBackup;
import com.javafxlogin.core.ipc.InitiateReset;
import com.javafxlogin.core.ipc.KeepSecret;
import com.javafxlogin.core.ipc.ListAccounts;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.ReadSecret;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SecretRevealed;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionToken;
import com.javafxlogin.core.store.SchemaMigrations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: backing a deployment up and restoring it somewhere else.
 *
 * <p>Issue #14 and ADR-0006. Two services are opened over two directories, which is what "a
 * different machine" means to a suite: a Backup written by one is handed to the other, and nothing
 * either of them keeps beside its store — the SecretVault, the MachineKey, the chain's key — is
 * shared between them. A backup that only restored where it was written would fail here, which
 * is the point of testing it this way rather than by restoring over the store it came from.
 *
 * <p>The file itself is asserted in {@code com.javafxlogin.core.backup.BackupFileTest}. What is
 * asserted here is everything the service decides around it: who may ask, what travels, what does
 * not, and that every way of refusing an import leaves the store exactly as it was.
 */
class BackupTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  /** Whoever administers the machine the Backup is being restored onto, and is about to lose it. */
  private static final String OTHER_ADMINISTRATOR = "juno.vale";

  private static final String OTHER_ADMINISTRATOR_PASSWORD = "Third-Horse-3";
  private static final String OTHER_OPERATOR = "mallory.quill";
  private static final String OTHER_OPERATOR_PASSWORD = "Fourth-Horse-4";

  private static final char[] BACKUP_PASSWORD = "Backup-Horse-Battery-5".toCharArray();

  /** What somebody chooses for themselves at the enrolment screen in these tests. */
  private static final String ENROLLED_PASSWORD = "Enrolled-Horse-6";

  /** The machine the Backup is written on. */
  @TempDir Path machine;

  /** The machine it is restored onto: a different directory, and every key file of its own. */
  @TempDir Path replacement;

  /** Where the file itself lives, which is neither service's own directory. */
  @TempDir Path shelf;

  private ServiceHarness here;
  private ServiceHarness there;

  @BeforeEach
  void openTwoDeployments() {
    here = ServiceHarness.cheap(machine);
    here.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    here.provisionOperator(OPERATOR, OPERATOR_PASSWORD);

    there = ServiceHarness.cheap(replacement);
    there.bootstrap(OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);
    there.provisionOperator(OTHER_OPERATOR, OTHER_OPERATOR_PASSWORD);
  }

  @AfterEach
  void closeBoth() {
    here.close();
    there.close();
  }

  /** Criterion 1: the Accounts and the configuration, under a password typed at the time. */
  @Test
  void anAdministratorWritesABackupOfTheAccountsAndTheConfiguration() {
    SessionToken administrator = administer(here);

    Response response = here.send(new ExportBackup(administrator, backupFile(), BACKUP_PASSWORD));

    Backup backup = assertInstanceOf(BackupExported.class, response).backup();
    assertEquals(2, backup.accounts(), "the Administrator and the Operator");
    assertTrue(backup.settings() > 0, "the configuration should travel with them");
    assertTrue(Files.exists(backupFile()));
  }

  /**
   * Criterion 4, and the one that makes ADR-0006 mean something: the file restores on a machine that
   * knows none of the keys the one that wrote it kept.
   */
  @Test
  void aBackupRestoresOnADifferentMachine() {
    Backup written = writeABackupHere();

    Backup restored = backupIn(restoreThere());

    assertEquals(written, restored, "the import came to something other than the export");
    assertEquals(
        List.of(OPERATOR, ADMINISTRATOR),
        namesThere(),
        "the replacement machine holds the Accounts of the machine that died");
    assertInstanceOf(
        Granted.class,
        there.send(new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR)),
        "the Operator's own password should work on the replacement machine");
  }

  /** Criterion 5: the machine's own Accounts are gone rather than sitting beside the Backup's. */
  @Test
  void importReplacesTheStoreWholesaleAndNeverMerges() {
    writeABackupHere();

    restoreThere();

    List<String> names = namesThere();
    assertFalse(names.contains(OTHER_ADMINISTRATOR), () -> "the old deployment is still in " + names);
    assertFalse(names.contains(OTHER_OPERATOR), () -> "the old deployment is still in " + names);
    assertEquals(List.of(OPERATOR, ADMINISTRATOR), names);
  }

  /**
   * Criterion 2. The SecretVault is a file of its own and stays on the machine it belongs to, so an
   * Operator restored onto another one can log in and cannot reach a secret until they enrol again.
   *
   * <p>This is the sharpest thing the two-directory arrangement buys: had the Vault travelled, the
   * secret would come back here, and had the export been asserted against its own machine, it would
   * come back either way.
   */
  @Test
  void theSecretVaultDoesNotTravelWithTheBackup() {
    SessionToken administrator = administer(here);
    String enrolled = enrolAnOperator(here, administrator, "rowan.blythe");
    here.send(new Logout(administrator));
    SessionToken operator = admit(here, enrolled, ENROLLED_PASSWORD, Role.OPERATOR);
    here.send(new KeepSecret(operator, "the-warehouse", "hunter2-and-then-some".toCharArray()));
    assertInstanceOf(
        SecretRevealed.class,
        here.send(new ReadSecret(operator, "the-warehouse")),
        "the secret should be readable on the machine that kept it");
    here.send(new Logout(operator));
    writeABackupHere();

    restoreThere();

    SessionToken restored =
        assertInstanceOf(
                Granted.class,
                there.send(
                    new Authenticate(enrolled, ENROLLED_PASSWORD.toCharArray(), Role.OPERATOR)))
            .token();
    assertEquals(
        ErrorCode.NO_VAULT_ACCESS,
        errorOf(there.send(new ReadSecret(restored, "the-warehouse"))),
        "a secret from the other machine's Vault came back");
  }

  /**
   * Criterion 3. The Account travels; the Enrolment does not. The secret in somebody's pocket was
   * addressed to a machine that no longer exists, and the replacement does not honour it.
   */
  @Test
  void anEnrolmentInProgressIsNotResurrectedOnTheRestoredMachine() {
    SessionToken administrator = administer(here);
    EnrolmentIssued issued =
        assertInstanceOf(
            EnrolmentIssued.class,
            here.send(new CreateAccount(administrator, "halfway.through", Role.OPERATOR)));
    writeABackupWith(administrator);

    restoreThere();

    assertInstanceOf(
        Denied.class,
        there.send(
            new CompleteEnrolment(
                "halfway.through",
                issued.secret().toCharArray(),
                "Chosen-Horse-7".toCharArray())),
        "the secret from the machine that died was honoured on the replacement");
    assertInstanceOf(
        Denied.class,
        there.send(
            new Authenticate("halfway.through", "Chosen-Horse-7".toCharArray(), Role.OPERATOR)),
        "and it let somebody in");
  }

  /**
   * And the Account itself is still there, waiting. It is not transient even though what it was
   * waiting for is: dropping it would be losing a person to the timing of a Backup.
   */
  @Test
  void anAccountThatWasAwaitingEnrolmentIsRestoredStillAwaitingOne() {
    SessionToken administrator = administer(here);
    here.send(new CreateAccount(administrator, "halfway.through", Role.OPERATOR));
    here.send(
        new ChangeLanguagePreference(
            administrator, "halfway.through", Optional.of(Locale.forLanguageTag("es"))));
    writeABackupWith(administrator);

    restoreThere();

    AccountSummary restored = accountThere("halfway.through");
    assertTrue(restored.isAwaitingEnrolment(), "it should still be waiting for a password");
    assertEquals(Role.OPERATOR, restored.role());
    assertEquals(Optional.of(Locale.forLanguageTag("es")), restored.languagePreference());
  }

  /**
   * The sharp case the ordinary one hides: an established Operator whose password an Administrator
   * took away yesterday. Backing that Account up as "nothing" would mean a reset on Monday quietly
   * deleting somebody from every Backup taken before they enrolled again.
   */
  @Test
  void anOperatorWhosePasswordWasResetStillTravels() {
    SessionToken administrator = administer(here);
    assertInstanceOf(
        EnrolmentIssued.class, here.send(new InitiateReset(administrator, OPERATOR)));
    writeABackupWith(administrator);

    restoreThere();

    AccountSummary restored = accountThere(OPERATOR);
    assertTrue(restored.isAwaitingEnrolment(), "it should be waiting for a password, as it was");
    assertEquals(Role.OPERATOR, restored.role());
  }

  /**
   * An Account restored awaiting enrolment waits on a secret this machine generated and told nobody,
   * because the schema will not hold an Account with neither a password nor an enrolment. It is a
   * placeholder and not a way in: the Administrator issues a real one from the panel.
   */
  @Test
  void anAccountRestoredAwaitingEnrolmentCanBeGivenAFreshSecret() {
    SessionToken administrator = administer(here);
    here.send(new InitiateReset(administrator, OPERATOR));
    writeABackupWith(administrator);
    restoreThere();

    SessionToken restoredAdministrator =
        administer(there, ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    EnrolmentIssued reissued =
        assertInstanceOf(
            EnrolmentIssued.class, there.send(new InitiateReset(restoredAdministrator, OPERATOR)));
    there.send(new Logout(restoredAdministrator));
    there.send(
        new CompleteEnrolment(
            OPERATOR, reissued.secret().toCharArray(), "Chosen-Horse-7".toCharArray()));

    assertInstanceOf(
        Granted.class,
        there.send(new Authenticate(OPERATOR, "Chosen-Horse-7".toCharArray(), Role.OPERATOR)),
        "the restored Account never became usable again");
  }

  /**
   * A wrap in the SecretVault is keyed by a name, and after a wholesale replace every name in it
   * belongs to somebody who no longer exists. One left behind would be a restored Account inheriting
   * a local namesake's way into this machine's Vault, which nobody decided to give them.
   */
  @Test
  void aRestoredAccountDoesNotInheritTheVaultAccessOfALocalNamesake() {
    String sharedName = "rowan.blythe";
    SessionToken localAdministrator =
        administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);
    enrolAnOperator(there, localAdministrator, sharedName);
    there.send(new Logout(localAdministrator));
    SessionToken local = admit(there, sharedName, ENROLLED_PASSWORD, Role.OPERATOR);
    there.send(new KeepSecret(local, "the-warehouse", "hunter2-and-then-some".toCharArray()));
    there.send(new Logout(local));

    SessionToken administrator = administer(here);
    enrolAnOperator(here, administrator, sharedName);
    writeABackupWith(administrator);
    restoreThere();

    SessionToken restored = admit(there, sharedName, ENROLLED_PASSWORD, Role.OPERATOR);
    assertEquals(
        ErrorCode.NO_VAULT_ACCESS,
        errorOf(there.send(new ReadSecret(restored, "the-warehouse"))),
        "a namesake inherited the wrapped DataKey of the Account they replaced");
  }

  /** The configuration travels, which is the other half of criterion 1. */
  @Test
  void whatTheDeploymentWasConfiguredToDoTravelsWithIt() {
    SessionToken administrator = administer(here);
    here.send(new ChangeInactivityPeriod(administrator, InactivityPeriod.of(Duration.ofMinutes(42))));
    writeABackupWith(administrator);

    restoreThere();

    SessionToken restored = administer(there, ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    assertEquals(
        Optional.of(Duration.ofMinutes(42)),
        sessionOf(there, restored).expiresIn(),
        "the restored deployment reads the inactivity period the Backup carried");
  }

  /** What an Account holds goes with it: the language its holder reads, and what it has failed. */
  @Test
  void whatAnAccountHoldsTravelsWithIt() {
    SessionToken administrator = administer(here);
    here.send(
        new ChangeLanguagePreference(
            administrator, OPERATOR, Optional.of(Locale.forLanguageTag("es"))));
    writeABackupWith(administrator);

    restoreThere();

    AccountSummary restored =
        listedThere(administer(there, ADMINISTRATOR, ADMINISTRATOR_PASSWORD)).stream()
            .filter(account -> account.name().equals(OPERATOR))
            .findFirst()
            .orElseThrow();
    assertEquals(Optional.of(Locale.forLanguageTag("es")), restored.languagePreference());
    assertEquals(PasswordStrength.ACCEPTABLE, restored.passwordStrength().orElseThrow());
  }

  /** Criterion 7, first half. */
  @Test
  void writingABackupIsRecordedAsAnAuthenticationEvent() throws IOException {
    SessionToken administrator = administer(here);

    here.send(new ExportBackup(administrator, backupFile(), BACKUP_PASSWORD));

    String record = recordOf(machine);
    assertTrue(
        record.contains(AuthenticationEventType.BACKUP_EXPORTED.name()),
        () -> "no export in the record: " + record);
  }

  /**
   * Criterion 7, second half. It is recorded on the machine's own record, which is not in the Backup
   * and does not travel — so the entries either side of this one are the deployment that was here.
   */
  @Test
  void restoringABackupIsRecordedAsAnAuthenticationEvent() throws IOException {
    writeABackupHere();

    restoreThere();

    String record = recordOf(replacement);
    assertTrue(
        record.contains(AuthenticationEventType.BACKUP_IMPORTED.name()),
        () -> "no import in the record: " + record);
    assertTrue(
        record.contains(OTHER_ADMINISTRATOR),
        "the import should be recorded against whoever asked for it, who no longer exists");
  }

  /** Criterion 8, and the part of it that matters: the store is untouched. */
  @Test
  void aBackupOpenedWithTheWrongPasswordIsRefusedAndChangesNothing() {
    writeABackupHere();
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);

    Response response =
        there.send(
            new ImportBackup(administrator, backupFile(), "Not-The-Password-9".toCharArray()));

    assertEquals(ErrorCode.BACKUP_NOT_READ, errorOf(response));
    assertEquals(
        List.of(OTHER_ADMINISTRATOR, OTHER_OPERATOR),
        listedThere(administrator).stream().map(AccountSummary::name).sorted().toList());
  }

  /** The same answer for a file somebody damaged, and the same store afterwards. */
  @Test
  void aDamagedBackupIsRefusedAndChangesNothing() throws IOException {
    writeABackupHere();
    char[] damaged = Files.readString(backupFile()).toCharArray();
    int somewhereInTheCiphertext = Files.readString(backupFile()).indexOf("\"contents\":\"") + 20;
    damaged[somewhereInTheCiphertext] = damaged[somewhereInTheCiphertext] == 'A' ? 'B' : 'A';
    Files.writeString(backupFile(), new String(damaged));
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);

    Response response = there.send(new ImportBackup(administrator, backupFile(), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_NOT_READ, errorOf(response));
    assertEquals(
        List.of(OTHER_ADMINISTRATOR, OTHER_OPERATOR),
        listedThere(administrator).stream().map(AccountSummary::name).sorted().toList());
  }

  /** A path with nothing at it is a path to try again, and not a file that would not open. */
  @Test
  void aBackupThatIsNotThereIsRefusedAsASourceRatherThanAsAPassword() {
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);

    Response response =
        there.send(
            new ImportBackup(administrator, shelf.resolve("nothing.jflb"), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_SOURCE_REFUSED, errorOf(response));
  }

  /**
   * A restore that would leave nobody able to administer the deployment is refused before anything
   * is written: there is no way back from one, because the FirstRunWizard is offered only while no
   * Administrator exists and this store would have had one.
   */
  @Test
  void aBackupThatNamesNoAdministratorIsRefusedBeforeAnythingIsWritten() throws IOException {
    Path operatorsOnly = shelf.resolve("operators-only.jflb");
    BackupFile.writeTo(
        operatorsOnly,
        new BackupContents(schemaVersionHere(), List.of(anOperatorRow()), Map.of()),
        BACKUP_PASSWORD,
        ServiceHarness.CHEAP);
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);

    Response response = there.send(new ImportBackup(administrator, operatorsOnly, BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_HAS_NO_ADMINISTRATOR, errorOf(response));
    assertEquals(2, listedThere(administrator).size());
  }

  /** A Backup from another version of the product is refused rather than read hopefully. */
  @Test
  void aBackupFromAnotherVersionOfTheProductIsRefused() throws IOException {
    Path fromTheFuture = shelf.resolve("later.jflb");
    BackupFile.writeTo(
        fromTheFuture,
        new BackupContents(schemaVersionHere() + 1, List.of(anAdministratorRow()), Map.of()),
        BACKUP_PASSWORD,
        ServiceHarness.CHEAP);
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);

    Response response = there.send(new ImportBackup(administrator, fromTheFuture, BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_NOT_THIS_SCHEMA, errorOf(response));
    assertEquals(2, listedThere(administrator).size());
  }

  /**
   * The Session that restored a Backup named an Account in a deployment that no longer exists, so it
   * ends with it. Anything else would be the one moment in this system where a Session outlives the
   * store the Account it names lived in.
   */
  @Test
  void theSessionThatRestoredTheBackupIsOver() {
    writeABackupHere();
    SessionToken administrator = administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);
    there.send(new ImportBackup(administrator, backupFile(), BACKUP_PASSWORD));

    Response afterwards = there.send(new ListAccounts(administrator));

    assertInstanceOf(SessionEnded.class, afterwards);
  }

  @Test
  void anOperatorMayNeitherWriteABackupNorRestoreOne() {
    SessionToken operator = admit(here, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR,
        errorOf(here.send(new ExportBackup(operator, backupFile(), BACKUP_PASSWORD))));
    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR,
        errorOf(here.send(new ImportBackup(operator, backupFile(), BACKUP_PASSWORD))));
    assertFalse(Files.exists(backupFile()), "a refused export still wrote a file");
  }

  /** A relative path would be resolved against a working directory the person asking cannot see. */
  @Test
  void aBackupIsNotWrittenToARelativePath() {
    SessionToken administrator = administer(here);

    Response response =
        here.send(new ExportBackup(administrator, Path.of("backup.jflb"), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_DESTINATION_REFUSED, errorOf(response));
  }

  /** Its own directory holds the store, the keys and the record. A Backup does not land there. */
  @Test
  void aBackupIsNotWrittenIntoTheServicesOwnDirectory() {
    SessionToken administrator = administer(here);

    Response response =
        here.send(
            new ExportBackup(administrator, machine.resolve("backup.jflb"), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_DESTINATION_REFUSED, errorOf(response));
  }

  /** Nor is one read from there, which would make an import a way to have the service open them. */
  @Test
  void aBackupIsNotReadFromTheServicesOwnDirectory() {
    SessionToken administrator = administer(here);

    Response response =
        here.send(
            new ImportBackup(
                administrator, machine.resolve("credentials.db"), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_SOURCE_REFUSED, errorOf(response));
  }

  /** Refused by the operating system, so that a symbolic link planted in between goes nowhere. */
  @Test
  void aBackupDoesNotOverwriteSomethingThatIsAlreadyThere() throws IOException {
    Files.writeString(backupFile(), "something somebody wanted");
    SessionToken administrator = administer(here);

    Response response = here.send(new ExportBackup(administrator, backupFile(), BACKUP_PASSWORD));

    assertEquals(ErrorCode.BACKUP_DESTINATION_REFUSED, errorOf(response));
    assertEquals("something somebody wanted", Files.readString(backupFile()));
  }

  private Path backupFile() {
    return shelf.resolve("backup.jflb");
  }

  private Backup writeABackupHere() {
    return writeABackupWith(administer(here));
  }

  /** The same, for a test that already holds the one Session this machine will grant. */
  private Backup writeABackupWith(SessionToken administrator) {
    Response response = here.send(new ExportBackup(administrator, backupFile(), BACKUP_PASSWORD));
    here.send(new Logout(administrator));
    return assertInstanceOf(BackupExported.class, response).backup();
  }

  private Response restoreThere() {
    SessionToken administrator =
        administer(there, OTHER_ADMINISTRATOR, OTHER_ADMINISTRATOR_PASSWORD);
    Response response = there.send(new ImportBackup(administrator, backupFile(), BACKUP_PASSWORD));
    assertInstanceOf(BackupImported.class, response);
    return response;
  }

  private static Backup backupIn(Response response) {
    return assertInstanceOf(BackupImported.class, response).backup();
  }

  /**
   * Who the replacement machine holds, in name order, read through a Session of the restored
   * deployment — and the Session is given back, because this machine grants one at a time and a test
   * that goes on to log somebody else in would otherwise be refused for the wrong reason.
   */
  private List<String> namesThere() {
    SessionToken administrator = administer(there, ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    try {
      return listedThere(administrator).stream().map(AccountSummary::name).sorted().toList();
    } finally {
      there.send(new Logout(administrator));
    }
  }

  private List<AccountSummary> listedThere(SessionToken administrator) {
    return assertInstanceOf(AccountsListed.class, there.send(new ListAccounts(administrator)))
        .accounts();
  }

  /** Creates an Operator and turns the secret into a password, which is what gives it a Vault wrap. */
  private static String enrolAnOperator(
      ServiceHarness harness, SessionToken administrator, String name) {
    EnrolmentIssued issued =
        assertInstanceOf(
            EnrolmentIssued.class,
            harness.send(new CreateAccount(administrator, name, Role.OPERATOR)));
    harness.send(
        new CompleteEnrolment(
            name, issued.secret().toCharArray(), ENROLLED_PASSWORD.toCharArray()));
    return name;
  }

  /** One Account on the replacement machine, read through a Session that is given back after. */
  private AccountSummary accountThere(String accountName) {
    SessionToken administrator = administer(there, ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    try {
      return listedThere(administrator).stream()
          .filter(account -> account.name().equals(accountName))
          .findFirst()
          .orElseThrow(
              () -> new AssertionError("no Account named " + accountName + " on the replacement"));
    } finally {
      there.send(new Logout(administrator));
    }
  }

  private SessionToken administer(ServiceHarness harness) {
    return administer(harness, ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
  }

  private static SessionToken administer(
      ServiceHarness harness, String accountName, String password) {
    Response response =
        harness.send(new Authenticate(accountName, password.toCharArray(), Role.ADMINISTRATOR));
    return assertInstanceOf(Granted.class, response).token();
  }

  private static SessionToken admit(
      ServiceHarness harness, String accountName, String password, Role role) {
    Response response = harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response).token();
  }

  private static SessionLive sessionOf(ServiceHarness harness, SessionToken token) {
    return assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  private static ErrorCode errorOf(Response response) {
    return assertInstanceOf(ErrorResponse.class, response).code();
  }

  private static String recordOf(Path directory) throws IOException {
    return Files.readString(ServiceHarness.eventLogIn(directory));
  }

  /** The schema this build's store is at, read rather than written down as a number here. */
  private static int schemaVersionHere() {
    return SchemaMigrations.latestVersion();
  }

  private static BackedUpAccount anOperatorRow() {
    return new BackedUpAccount(
        "nobody.incharge",
        Role.OPERATOR,
        "$argon2id$v=19$m=256,t=1,p=1$c2FsdA$aGFzaA",
        PasswordStrength.ACCEPTABLE,
        OffsetDateTime.parse("2026-03-01T09:00:00+01:00"),
        Optional.empty(),
        Optional.empty(),
        FailedAuthentications.none());
  }

  private static BackedUpAccount anAdministratorRow() {
    return new BackedUpAccount(
        "someone.incharge",
        Role.ADMINISTRATOR,
        "$argon2id$v=19$m=256,t=1,p=1$c2FsdA$aGFzaA",
        PasswordStrength.STRONG,
        OffsetDateTime.parse("2026-03-01T09:00:00+01:00"),
        Optional.empty(),
        Optional.empty(),
        FailedAuthentications.none());
  }
}
