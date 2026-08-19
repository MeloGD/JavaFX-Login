package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventType;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.ChangeOwnPassword;
import com.javafxlogin.core.ipc.CompleteEnrolment;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.DeleteAccount;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.InitiateReset;
import com.javafxlogin.core.ipc.KeepSecret;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.ReadSecret;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SecretRevealed;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the SecretVault as the AuthenticationService serves it.
 *
 * <p>Stories 55 to 63, ADR-0004 and ADR-0005. The Vault's own arithmetic is asserted in {@code
 * com.javafxlogin.core.vault.SecretVaultTest}; what is asserted here is everything the service
 * decides around it — who may ask, what an enrolment and a reset do to a wrapped key, and that the
 * Vault opens because a password derived the key rather than because an answer said yes.
 */
class SecretVaultAccessTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String CHOSEN_PASSWORD = "Another-Horse-2";
  private static final String RECHOSEN_PASSWORD = "A-Third-Horse-3";

  private static final String CONNECTION_STRING = "warehouse.database.password";
  private static final char[] A_SECRET = "sa/8Xk!connect".toCharArray();

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithItsAdministrator() {
    harness = ServiceHarness.cheap(directory);
    harness.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  // --- what a ProtectedFeature gets -----------------------------------------------------------

  /**
   * Criterion 1, end to end through the service: an Operator is enrolled, logs in, and the feature
   * behind the gate asks for a named secret and receives it.
   */
  @Test
  void aProtectedFeatureAsksForANamedSecretAndReceivesIt() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);

    assertInstanceOf(Ok.class, harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET)));

    Response response = harness.send(new ReadSecret(operator, CONNECTION_STRING));
    assertArrayEquals(A_SECRET, assertInstanceOf(SecretRevealed.class, response).secret());
  }

  /** A ProtectedFeature owed a credential that is not there is told so, rather than left waiting. */
  @Test
  void aNameNothingIsKeptUnderIsSaidPlainly() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);

    assertEquals(
        ErrorCode.NO_SUCH_SECRET, errorOf(harness.send(new ReadSecret(operator, "nothing.here"))));
  }

  /** Story 55 again: the Vault the next Operator opens is the same Vault, because the key is one. */
  @Test
  void anotherOperatorReadsWhatTheFirstOneKept() {
    SessionToken first = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(first, CONNECTION_STRING, A_SECRET));
    harness.send(new Logout(first));

    SessionToken second = enrolAndLogIn("juno.vale", RECHOSEN_PASSWORD);

    assertArrayEquals(
        A_SECRET,
        assertInstanceOf(SecretRevealed.class, harness.send(new ReadSecret(second, CONNECTION_STRING)))
            .secret());
  }

  /**
   * Asking for a secret is not activity, like every other question about a Session. A
   * ProtectedFeature polling for a credential would otherwise keep alive the Session of somebody who
   * walked away from the screen — expiry is about the person, and the person is what the SessionGuard
   * reports.
   */
  @Test
  void readingASecretIsNotActivity() {
    harness.inactivityPeriodIs(InactivityPeriod.of(Duration.ofMinutes(15)));
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));

    harness.clock().passes(Duration.ofMinutes(10));
    harness.send(new ReadSecret(operator, CONNECTION_STRING));
    harness.clock().passes(Duration.ofMinutes(6));

    assertInstanceOf(SessionEnded.class, harness.send(new ReportActivity(operator)));
  }

  /** A Session that is over reaches nothing, and is told what happened to it rather than refused. */
  @Test
  void aSessionThatHasEndedReachesNoSecret() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));
    harness.send(new Logout(operator));

    assertInstanceOf(SessionEnded.class, harness.send(new ReadSecret(operator, CONNECTION_STRING)));
  }

  // --- the unlock is cryptographic ------------------------------------------------------------

  /**
   * The assertion this whole ticket exists for. A store edited so that some other password
   * authenticates is exactly what a patched binary buys an attacker: the answer comes back {@link
   * Granted}, the boolean says yes — and the Vault stays shut, because no password was typed that
   * derives the key which unwraps this Account's copy of the DataKey.
   */
  @Test
  void aSessionGrantedWithoutTheRealPasswordOpensNoVault() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));
    harness.send(new Logout(operator));

    replaceThePasswordHashOf(OPERATOR, "Some-Other-Horse-9");
    SessionToken granted = admitted(attempt(OPERATOR, "Some-Other-Horse-9"));

    assertEquals(
        ErrorCode.NO_VAULT_ACCESS,
        errorOf(harness.send(new ReadSecret(granted, CONNECTION_STRING))),
        "authentication was enough to open the Vault");
  }

  /**
   * An Operator provisioned before this Vault existed reaches the ProtectedFeature and no secret. The
   * upgrade path is a real one — the CredentialStore is older than the Vault — and it must admit
   * people rather than refuse them.
   */
  @Test
  void anOperatorWithNoWrappedCopyIsAdmittedAndReachesNoSecret() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);

    SessionToken operator = admitted(attempt(OPERATOR, CHOSEN_PASSWORD));

    assertEquals(
        ErrorCode.NO_VAULT_ACCESS, errorOf(harness.send(new ReadSecret(operator, CONNECTION_STRING))));
    assertEquals(
        ErrorCode.NO_VAULT_ACCESS,
        errorOf(harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET))));
  }

  // --- the Administrator ----------------------------------------------------------------------

  /**
   * Criterion 7: refused by the service, not by the client, and refused whichever way round the
   * request goes. What it does not claim is that the secrets are protected from the Administrator —
   * see {@link #anAdministratorReachesTheVaultByCreatingAnOperator}, which is that claim being
   * disproved on purpose.
   */
  @Test
  void everyVaultOperationFromAnAdministratorIsRefusedByTheService() {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));

    assertEquals(
        ErrorCode.NOT_AN_OPERATOR,
        errorOf(harness.send(new ReadSecret(administrator, CONNECTION_STRING))));
    assertEquals(
        ErrorCode.NOT_AN_OPERATOR,
        errorOf(harness.send(new KeepSecret(administrator, CONNECTION_STRING, A_SECRET))));
  }

  /** ADR-0005's entire security value: the refusal is written down where it cannot be edited. */
  @Test
  void theRefusalIsWrittenToTheRecord() throws IOException {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));

    harness.send(new ReadSecret(administrator, CONNECTION_STRING));

    assertTrue(
        recordedEvents()
            .contains(AuthenticationEventType.VAULT_REFUSED_TO_AN_ADMINISTRATOR + "," + ADMINISTRATOR),
        () -> "the refusal is not in the record: " + recordedEventsText());
  }

  /**
   * ADR-0005, stated as a test rather than as a paragraph. The Administrator reaches every secret in
   * the Vault by creating an Operator and enrolling it, which is two requests — and the point is not
   * that this is prevented, because it is not. It is that both requests are in the record.
   */
  @Test
  void anAdministratorReachesTheVaultByCreatingAnOperator() throws IOException {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));
    harness.send(new Logout(operator));

    SessionToken theirOwn = enrolAndLogIn("quiet.newcomer", RECHOSEN_PASSWORD);

    assertArrayEquals(
        A_SECRET,
        assertInstanceOf(
                SecretRevealed.class, harness.send(new ReadSecret(theirOwn, CONNECTION_STRING)))
            .secret(),
        "the detour ADR-0005 describes no longer works, so the ADR is now wrong");
    List<String> record = recordedEvents();
    assertTrue(
        record.contains(AuthenticationEventType.ACCOUNT_CREATED + ",quiet.newcomer")
            && record.contains(AuthenticationEventType.ENROLMENT_COMPLETED + ",quiet.newcomer"),
        () -> "the detour left no trail: " + recordedEventsText());
  }

  // --- enrolment, reset and rotation ----------------------------------------------------------

  /** Criterion 4: completing an enrolment is what wraps the DataKey for an Operator. */
  @Test
  void completingAnEnrolmentIsWhatGivesAnOperatorAWrappedCopy() {
    EnrolmentIssued issued = createTheOperator();

    assertEquals(0, wrapsFor(OPERATOR), "an Account created is an Account with no key");

    harness.send(
        new CompleteEnrolment(
            OPERATOR, issued.secret().toCharArray(), CHOSEN_PASSWORD.toCharArray()));

    assertEquals(1, wrapsFor(OPERATOR));
  }

  /**
   * Criterion 4, where it can actually be seen: the salt the Vault derives its key with is not the
   * salt inside the stored authentication hash. One password, two derivations, nothing shared —
   * which is what stops the stored hash from being key material, whatever a later build imports.
   */
  @Test
  void theVaultsSaltIsNotTheOneInsideTheAuthenticationHash() {
    enrolAndLogInAndOut(OPERATOR, CHOSEN_PASSWORD);

    assertNotEquals(saltInsideThePasswordHashOf(OPERATOR), saltOfTheWrapFor(OPERATOR));
  }

  /** Criterion 5: rotating a password keeps the secrets, and the old password stops working. */
  @Test
  void changingAPasswordRewrapsRatherThanLosingTheSecrets() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));

    assertInstanceOf(
        Ok.class,
        harness.send(
            new ChangeOwnPassword(
                operator, CHOSEN_PASSWORD.toCharArray(), RECHOSEN_PASSWORD.toCharArray())));
    harness.send(new Logout(operator));

    assertInstanceOf(Denied.class, attempt(OPERATOR, CHOSEN_PASSWORD));
    SessionToken again = admitted(attempt(OPERATOR, RECHOSEN_PASSWORD));
    assertArrayEquals(
        A_SECRET,
        assertInstanceOf(SecretRevealed.class, harness.send(new ReadSecret(again, CONNECTION_STRING)))
            .secret());
  }

  /** The Session that changed the password keeps working: the key it holds is the same key. */
  @Test
  void theSessionThatChangedThePasswordStillReachesTheVault() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));

    harness.send(
        new ChangeOwnPassword(
            operator, CHOSEN_PASSWORD.toCharArray(), RECHOSEN_PASSWORD.toCharArray()));

    assertArrayEquals(
        A_SECRET,
        assertInstanceOf(
                SecretRevealed.class, harness.send(new ReadSecret(operator, CONNECTION_STRING)))
            .secret());
  }

  /** A live Session is not proof of who is at the keyboard, so the current password is asked for. */
  @Test
  void changingAPasswordWithTheWrongCurrentOneIsRefusedAndCounted() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);

    Response response =
        harness.send(
            new ChangeOwnPassword(
                operator, "Not-The-Password-8".toCharArray(), RECHOSEN_PASSWORD.toCharArray()));

    assertEquals(DeniedReason.AUTH_FAILED, assertInstanceOf(Denied.class, response).reason());
    assertEquals(1, failedAuthenticationsOf(OPERATOR), "guessing here was free");
  }

  /** The new password goes through the same policy as one chosen at enrolment. */
  @Test
  void aNewPasswordThatBreaksThePolicyIsRefusedAndChangesNothing() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);

    Response response =
        harness.send(
            new ChangeOwnPassword(operator, CHOSEN_PASSWORD.toCharArray(), "short".toCharArray()));

    assertInstanceOf(com.javafxlogin.core.ipc.PolicyRefused.class, response);
    harness.send(new Logout(operator));
    assertInstanceOf(Granted.class, attempt(OPERATOR, CHOSEN_PASSWORD));
  }

  /**
   * A reset takes the Vault away with the password, and the enrolment that follows gives it back —
   * from the machine's copy of the DataKey, with nobody but the Operator present.
   */
  @Test
  void aResetTakesTheWrappedCopyAwayAndEnrolmentRestoresIt() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);
    harness.send(new KeepSecret(operator, CONNECTION_STRING, A_SECRET));
    harness.send(new Logout(operator));

    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));
    EnrolmentIssued reissued =
        assertInstanceOf(
            EnrolmentIssued.class, harness.send(new InitiateReset(administrator, OPERATOR)));
    harness.send(new Logout(administrator));

    assertEquals(0, wrapsFor(OPERATOR), "the password was taken away and the key was not");

    harness.send(new CompleteEnrolment(OPERATOR, reissued.secret().toCharArray(),
        RECHOSEN_PASSWORD.toCharArray()));
    SessionToken again = admitted(attempt(OPERATOR, RECHOSEN_PASSWORD));

    assertArrayEquals(
        A_SECRET,
        assertInstanceOf(SecretRevealed.class, harness.send(new ReadSecret(again, CONNECTION_STRING)))
            .secret(),
        "a reset cost the Operator their secrets");
  }

  // --- deleting an Operator -------------------------------------------------------------------

  /** Criterion 6: revocation is real, because it is the wrapped copy that goes. */
  @Test
  void deletingAnOperatorDestroysTheirWrappedCopy() {
    enrolAndLogInAndOut(OPERATOR, CHOSEN_PASSWORD);
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));

    assertInstanceOf(Ok.class, harness.send(new DeleteAccount(administrator, OPERATOR)));

    assertEquals(0, wrapsFor(OPERATOR));
    harness.send(new Logout(administrator));
    assertInstanceOf(Denied.class, attempt(OPERATOR, CHOSEN_PASSWORD));
  }

  /** There is one Administrator, and a deployment nobody can administer is not an improvement. */
  @Test
  void theAdministratorCannotBeDeleted() {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));

    assertEquals(
        ErrorCode.CANNOT_DELETE_THE_ADMINISTRATOR,
        errorOf(harness.send(new DeleteAccount(administrator, ADMINISTRATOR))));
  }

  /** Said plainly, for the reason clearing a Lockout is: an Administrator who mistyped is told. */
  @Test
  void deletingANameNobodyHoldsIsSaidPlainly() {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));

    assertEquals(
        ErrorCode.NO_SUCH_ACCOUNT,
        errorOf(harness.send(new DeleteAccount(administrator, "nobody.at.all"))));
  }

  /** An Operator deleting Accounts would be the administration panel with the gate removed. */
  @Test
  void anOperatorMayNotDeleteAnAccount() {
    SessionToken operator = enrolAndLogIn(OPERATOR, CHOSEN_PASSWORD);

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR,
        errorOf(harness.send(new DeleteAccount(operator, ADMINISTRATOR))));
  }

  // --- the file itself ------------------------------------------------------------------------

  /** Criterion 9: a separate file beside the store, and nothing about secrets inside the store. */
  @Test
  void theVaultIsItsOwnFileBesideTheCredentialStore() {
    enrolAndLogInAndOut(OPERATOR, CHOSEN_PASSWORD);

    assertTrue(Files.exists(ServiceHarness.vaultFileIn(directory)), "there is no Vault file");
    assertTrue(Files.exists(ServiceHarness.machineKeyFileIn(directory)), "there is no MachineKey");
    assertEquals(
        List.of(),
        tablesOf(ServiceHarness.storeFileIn(directory)).stream()
            .filter(table -> table.contains("secret") || table.contains("key"))
            .toList(),
        "the CredentialStore has grown a table about secrets");
  }

  // --- the plumbing this test needs -----------------------------------------------------------

  private EnrolmentIssued createTheOperator() {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));
    EnrolmentIssued issued =
        assertInstanceOf(
            EnrolmentIssued.class,
            harness.send(new CreateAccount(administrator, OPERATOR, Role.OPERATOR)));
    harness.send(new Logout(administrator));
    return issued;
  }

  /** Creates an Account, enrols it against a password of its own, and logs in with it. */
  private SessionToken enrolAndLogIn(String name, String password) {
    SessionToken administrator = admitted(attemptAs(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));
    EnrolmentIssued issued =
        assertInstanceOf(
            EnrolmentIssued.class,
            harness.send(new CreateAccount(administrator, name, Role.OPERATOR)));
    harness.send(new Logout(administrator));
    assertInstanceOf(
        Ok.class,
        harness.send(
            new CompleteEnrolment(name, issued.secret().toCharArray(), password.toCharArray())));
    return admitted(attempt(name, password));
  }

  private void enrolAndLogInAndOut(String name, String password) {
    harness.send(new Logout(enrolAndLogIn(name, password)));
  }

  private Response attempt(String accountName, String password) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), Role.OPERATOR));
  }

  private Response attemptAs(String accountName, String password) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), Role.ADMINISTRATOR));
  }

  private static SessionToken admitted(Response response) {
    return assertInstanceOf(Granted.class, response).token();
  }

  private static ErrorCode errorOf(Response response) {
    return assertInstanceOf(ErrorResponse.class, response).code();
  }

  /** What a patched binary would achieve: some password, any password, authenticating. */
  private void replaceThePasswordHashOf(String accountName, String password) {
    String hash = new Authenticator(ServiceHarness.CHEAP).hash(password.toCharArray());
    inTheStore(
        "UPDATE accounts SET password_hash = ? WHERE name = ?",
        statement -> {
          statement.setString(1, hash);
          statement.setString(2, accountName);
          statement.executeUpdate();
        });
  }

  private int failedAuthenticationsOf(String accountName) {
    return readFromTheStore(
        "SELECT failed_authentications FROM accounts WHERE name = '" + accountName + "'");
  }

  /** The salt out of the PHC string, which is the fourth of its dollar-separated fields. */
  private String saltInsideThePasswordHashOf(String accountName) {
    String phc =
        textFromTheStore(
            "SELECT password_hash FROM accounts WHERE name = \'" + accountName + "\'");
    return phc.split("\\$")[4];
  }

  private String saltOfTheWrapFor(String accountName) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.vaultFileIn(directory));
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT kdf_salt FROM data_key_wraps WHERE account_name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> accountName + " holds no wrapped copy of the DataKey");
        return Base64.getEncoder().withoutPadding().encodeToString(results.getBytes(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private String textFromTheStore(String sql) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.storeFileIn(directory));
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet results = statement.executeQuery()) {
      assertTrue(results.next(), () -> "nothing came back from " + sql);
      return results.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private int wrapsFor(String accountName) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.vaultFileIn(directory));
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM data_key_wraps WHERE account_name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? results.getInt(1) : -1;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> recordedEvents() throws IOException {
    return Files.readAllLines(ServiceHarness.eventLogIn(directory), StandardCharsets.UTF_8).stream()
        .skip(1)
        .map(SecretVaultAccessTest::typeAndSubjectOf)
        .toList();
  }

  private String recordedEventsText() {
    try {
      return String.join("\n", recordedEvents());
    } catch (IOException e) {
      return "the record could not be read: " + e;
    }
  }

  /** The last two of the four fields the log writes as chain, moment, event, subject. */
  private static String typeAndSubjectOf(String line) {
    String[] fields = line.split(",");
    return fields.length < 4 ? line : unquoted(fields[2]) + "," + unquoted(fields[3]);
  }

  private static String unquoted(String field) {
    return field.replace("\"", "");
  }

  private int readFromTheStore(String sql) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.storeFileIn(directory));
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet results = statement.executeQuery()) {
      return results.next() ? results.getInt(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> tablesOf(Path file) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement statement =
            connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'");
        ResultSet results = statement.executeQuery()) {
      List<String> tables = new java.util.ArrayList<>();
      while (results.next()) {
        tables.add(results.getString(1));
      }
      return tables;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void inTheStore(String sql, StatementWork work) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.storeFileIn(directory));
        PreparedStatement statement = connection.prepareStatement(sql)) {
      work.run(statement);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private interface StatementWork {
    void run(PreparedStatement statement) throws SQLException;
  }
}
