package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.EnrolmentSecret;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.CompleteEnrolment;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.InitiateReset;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: how an Operator comes to have a password, without the Administrator ever choosing it.
 *
 * <p>Stories 18 to 31, and ASVS 5.0 §6.4.6 behind them. What is asserted here is mostly about what
 * nobody knows: the Administrator knows a secret and never a password, the secret is knowable once,
 * and the Account whose password was taken away says so to the person it belongs to and to nobody
 * else.
 */
class EnrolmentTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String CHOSEN_PASSWORD = "Another-Horse-2";

  /** What V005 writes, and what every test here that does not say otherwise runs against. */
  private static final Duration SECRET_LASTS_FOR = Duration.ofHours(72);

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

  // --- the whole of it, once ------------------------------------------------------------------

  /**
   * Stories 21 to 23 end to end: the Administrator makes an Account and is handed a secret, the
   * person who will use it turns that secret into a password of their own, and then they log in.
   * Nothing anywhere in this test tells the Administrator what the password is.
   */
  @Test
  void anOperatorSetsTheirOwnPasswordAndReachesTheProtectedFeature() {
    EnrolmentIssued issued = createTheOperator();

    assertInstanceOf(Ok.class, enrol(OPERATOR, issued.secret(), CHOSEN_PASSWORD));

    assertInstanceOf(Granted.class, attempt(OPERATOR, CHOSEN_PASSWORD));
  }

  // --- the secret ----------------------------------------------------------------------------

  /** Criterion 1: creating an Account carries no password and answers with something to hand over. */
  @Test
  void creatingAnAccountAnswersWithASecretAndTheMomentItRunsOut() {
    EnrolmentIssued issued = createTheOperator();

    assertEquals(26, issued.secret().replace("-", "").length(), "not 128 bits of secret");
    assertEquals(harness.clock().wallTime().plus(SECRET_LASTS_FOR), issued.expiresAt());
  }

  /** Criterion 2: shown once. There is no request that reads it back, and nothing keeps it. */
  @Test
  void theSecretIsNeverReadableAgain() throws IOException {
    String secret = createTheOperator().secret();

    assertFalse(
        everythingWrittenToDisk().contains(withoutDashes(secret)),
        "the secret was written down somewhere the service could read it again");
  }

  /** Criterion 3: hashed, and the hash is not the secret. */
  @Test
  void theSecretIsStoredAsAHashAndNotAsItself() {
    String secret = createTheOperator().secret();

    String stored = readFromTheStore("enrolment_secret_hash", OPERATOR);

    assertEquals(64, stored.length(), "not a SHA-256 written as hexadecimal");
    assertNotEquals(withoutDashes(secret), stored);
  }

  /**
   * Criterion 3 again, and the half of it that matters most: the record says an enrolment was
   * issued, and never what it was. A log that held the secret would be the copy that outlives the
   * one moment the secret is supposed to exist for.
   */
  @Test
  void theRecordSaysAnEnrolmentWasIssuedAndNotWhatItWas() throws IOException {
    String secret = createTheOperator().secret();

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));

    assertTrue(recorded.contains("ENROLMENT_SECRET_ISSUED"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains("ACCOUNT_CREATED"), () -> "not recorded: " + recorded);
    assertFalse(recorded.contains(withoutDashes(secret)), "the secret is in the audit log");
    assertFalse(recorded.contains(secret), "the secret is in the audit log");
  }

  /** Criterion 5: it expires. */
  @Test
  void aSecretThatHasRunOutIsRefused() {
    String secret = createTheOperator().secret();

    harness.clock().passes(SECRET_LASTS_FOR);

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, secret, CHOSEN_PASSWORD));
  }

  @Test
  void aSecretIsGoodUntilTheMomentItRunsOut() {
    String secret = createTheOperator().secret();

    harness.clock().passes(SECRET_LASTS_FOR.minusSeconds(1));

    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, CHOSEN_PASSWORD));
  }

  /** How long it lasts is configuration read from the store, not a constant in the service. */
  @Test
  void howLongASecretLastsIsWhateverTheStoreSays() {
    harness.enrolmentSecretLastsFor(Duration.ofHours(1));

    String secret = createTheOperator().secret();
    harness.clock().passes(Duration.ofMinutes(61));

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, secret, CHOSEN_PASSWORD));
  }

  /**
   * A clock set backwards must not turn three days into a decade, for the reason ADR-0010 gives
   * about a Lockout: whoever can move the machine's clock can rewrite this file directly anyway,
   * and an Administrator re-issues the secret this refuses.
   */
  @Test
  void aSecretNeverOutlastsTheTimeItWasIssuedFor() {
    String secret = createTheOperator().secret();

    harness.clock().theWallClockJumps(Duration.ofDays(-365));

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, secret, CHOSEN_PASSWORD));
  }

  /** Criterion 5 again: one use, and the second is refused. */
  @Test
  void aSecretIsConsumedByTheEnrolmentItCompletes() {
    String secret = createTheOperator().secret();
    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, CHOSEN_PASSWORD));

    assertEquals(
        Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, secret, "A-Third-Horse-3"));
  }

  /**
   * A password the policy refuses does not consume it. Somebody who chose a password one character
   * short would otherwise have to go back to the Administrator for another secret.
   */
  @Test
  void aRefusedPasswordLeavesTheSecretWhereItWas() {
    String secret = createTheOperator().secret();

    assertInstanceOf(PolicyRefused.class, enrol(OPERATOR, secret, "short"));

    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, CHOSEN_PASSWORD));
  }

  /** The password chosen here goes through the same rules as any other, inside the service. */
  @Test
  void theChosenPasswordIsAssessedByThePolicy() {
    String secret = createTheOperator().secret();

    PolicyRefused refused =
        assertInstanceOf(PolicyRefused.class, enrol(OPERATOR, secret, "abcdefghijkl"));

    assertTrue(
        refused.violations().contains(PolicyViolation.PASSWORD_WITHOUT_UPPERCASE),
        () -> "not the rules the policy applies: " + refused.violations());
  }

  /**
   * And the band it was given is recorded against the Account, as it is for anybody else. Until
   * somebody chooses one the column reads as the weakest band, which is V002's rule and not a
   * measurement: the band of a password nobody has chosen must not read as a strong one.
   */
  @Test
  void theBandOfTheChosenPasswordIsRecorded() {
    String secret = createTheOperator().secret();
    assertEquals(
        "WEAK", readFromTheStore("password_strength", OPERATOR), "before anybody chose one");

    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, "Sparrow-Kettle-Marsh-Lantern-7!"));

    assertNotEquals("WEAK", readFromTheStore("password_strength", OPERATOR));
  }

  /** Every way of being wrong is the same refusal: the screen must not sort names into piles. */
  @Test
  void aWrongSecretIsRefusedInTheSameWordsAsANameNobodyHolds() {
    createTheOperator();

    Response forAWrongSecret = enrol(OPERATOR, otherSecret(), CHOSEN_PASSWORD);
    Response forANameNobodyHolds = enrol("nobody.here", otherSecret(), CHOSEN_PASSWORD);

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), forAWrongSecret);
    assertEquals(forAWrongSecret, forANameNobodyHolds);
  }

  @Test
  void textThatIsNotASecretAtAllIsTheSameRefusalAgain() {
    createTheOperator();

    assertEquals(
        Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, "hello", CHOSEN_PASSWORD));
  }

  /** An Account with a password of its own is waiting for nothing, and says so no differently. */
  @Test
  void anAccountThatIsNotAwaitingEnrolmentIsRefusedTheSameWay() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);

    assertEquals(
        Denied.because(DeniedReason.AUTH_FAILED),
        enrol(OPERATOR, otherSecret(), "A-Third-Horse-3"));
  }

  /**
   * The enrolment screen is the one place a credential for an Account awaiting enrolment can be
   * offered, so it is the one place guessing at one can happen. Leaving it uncounted would make
   * waiting for enrolment the single state in which guessing at this system is free.
   */
  @Test
  void aWrongSecretCountsTowardsTheLockoutLikeAnyOtherFailure() {
    String secret = createTheOperator().secret();

    for (int attempt = 1; attempt < 5; attempt++) {
      assertEquals(
          Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, otherSecret(), CHOSEN_PASSWORD));
    }

    assertEquals(
        Denied.lockedFor(Duration.ofMinutes(15)), enrol(OPERATOR, otherSecret(), CHOSEN_PASSWORD));
    assertEquals(
        Denied.lockedFor(Duration.ofMinutes(15)),
        enrol(OPERATOR, secret, CHOSEN_PASSWORD),
        "a locked Account is refused the right secret too");
  }

  @Test
  void completingAnEnrolmentForgetsTheFailuresBeforeIt() {
    String secret = createTheOperator().secret();
    assertEquals(
        Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, otherSecret(), CHOSEN_PASSWORD));

    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, CHOSEN_PASSWORD));

    assertEquals(0, Integer.parseInt(readFromTheStore("failed_authentications", OPERATOR)));
  }

  /**
   * Criterion 6: no Session, because the person sending it has not authenticated and cannot. The
   * Administrator who asked for the secret has logged out by the time this is sent, so there is no
   * Session anywhere on the machine for it to be riding on.
   */
  @Test
  void completingAnEnrolmentCarriesNoSession() {
    EnrolmentIssued issued = createTheOperator();

    assertInstanceOf(Ok.class, enrol(OPERATOR, issued.secret(), CHOSEN_PASSWORD));
  }

  /** Every enrolment, refused or completed, leaves a line behind. */
  @Test
  void whatHappensToAnEnrolmentIsRecorded() throws IOException {
    String secret = createTheOperator().secret();
    enrol(OPERATOR, otherSecret(), CHOSEN_PASSWORD);
    enrol(OPERATOR, secret, CHOSEN_PASSWORD);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));

    assertTrue(recorded.contains("ENROLMENT_FAILED"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains("ENROLMENT_COMPLETED"), () -> "not recorded: " + recorded);
  }

  // --- authenticating against an Account that has no password ---------------------------------

  /** Criterion 7 and story 30: a distinct refusal, so the client can send the person somewhere. */
  @Test
  void anAccountAwaitingEnrolmentIsRefusedWithAReasonOfItsOwn() {
    createTheOperator();

    assertEquals(
        Denied.because(DeniedReason.ENROLMENT_REQUIRED), attempt(OPERATOR, CHOSEN_PASSWORD));
  }

  /**
   * And it is not counted against the Account. Whoever guessed the name of an Account that has not
   * been enrolled yet could otherwise lock its holder out of their own enrolment before they ever
   * reached the screen.
   */
  @Test
  void beingSentToTheEnrolmentScreenIsNotAFailedAuthentication() {
    createTheOperator();

    for (int attempt = 0; attempt < 8; attempt++) {
      assertEquals(
          Denied.because(DeniedReason.ENROLMENT_REQUIRED), attempt(OPERATOR, CHOSEN_PASSWORD));
    }

    assertEquals(0, Integer.parseInt(readFromTheStore("failed_authentications", OPERATOR)));
  }

  @Test
  void beingSentToTheEnrolmentScreenIsRecorded() throws IOException {
    createTheOperator();

    attempt(OPERATOR, CHOSEN_PASSWORD);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(
        recorded.contains("AUTHENTICATION_REFUSED_ENROLMENT_REQUIRED"),
        () -> "not recorded: " + recorded);
  }

  // --- resets --------------------------------------------------------------------------------

  /** Criterion 8: the old password stops working at once, and not when the new one arrives. */
  @Test
  void aResetTakesTheOldPasswordAwayImmediately() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);

    resetThePasswordOf(OPERATOR);

    assertEquals(
        Denied.because(DeniedReason.ENROLMENT_REQUIRED), attempt(OPERATOR, CHOSEN_PASSWORD));
  }

  @Test
  void aResetLeavesNoHashOfTheOldPasswordBehind() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);
    String was = readFromTheStore("password_hash", OPERATOR);

    resetThePasswordOf(OPERATOR);

    assertEquals(Optional.empty(), Optional.ofNullable(readFromTheStore("password_hash", OPERATOR)));
    assertNotEquals(null, was, "the Operator had no password to take away");
  }

  /** Criterion 9: the Operator is told, at the moment they have proved the Account is theirs. */
  @Test
  void theOperatorIsToldAtTheirNextLoginThatTheirPasswordWasReset() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);
    Instant resetAt = harness.clock().wallTime();
    String secret = resetThePasswordOf(OPERATOR).secret();
    assertInstanceOf(Ok.class, enrol(OPERATOR, secret, "A-Third-Horse-3"));

    Granted granted = assertInstanceOf(Granted.class, attempt(OPERATOR, "A-Third-Horse-3"));

    assertEquals(Optional.of(resetAt), granted.passwordResetAt());
  }

  /** It is news, and news is said once. */
  @Test
  void theOperatorIsToldOnce() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);
    String secret = resetThePasswordOf(OPERATOR).secret();
    enrol(OPERATOR, secret, "A-Third-Horse-3");
    logOutOf(assertInstanceOf(Granted.class, attempt(OPERATOR, "A-Third-Horse-3")).token());

    Granted again = assertInstanceOf(Granted.class, attempt(OPERATOR, "A-Third-Horse-3"));

    assertEquals(Optional.empty(), again.passwordResetAt());
  }

  /** An Operator who was never reset is told nothing, which is what an ordinary login is. */
  @Test
  void anOrdinaryLoginIsToldNothing() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);

    Granted granted = assertInstanceOf(Granted.class, attempt(OPERATOR, CHOSEN_PASSWORD));

    assertEquals(Optional.empty(), granted.passwordResetAt());
  }

  /** Nor is a first enrolment: nobody took anything away from an Account that never had one. */
  @Test
  void aFirstEnrolmentIsNotAResetAnybodyIsToldAbout() {
    String secret = createTheOperator().secret();
    enrol(OPERATOR, secret, CHOSEN_PASSWORD);

    Granted granted = assertInstanceOf(Granted.class, attempt(OPERATOR, CHOSEN_PASSWORD));

    assertEquals(Optional.empty(), granted.passwordResetAt());
  }

  /** Criterion 10: a secret that was lost or ran out is not a dead end. */
  @Test
  void theAdministratorCanReissueASecretThatRanOut() {
    String lost = createTheOperator().secret();
    harness.clock().passes(SECRET_LASTS_FOR);
    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, lost, CHOSEN_PASSWORD));

    String reissued = resetThePasswordOf(OPERATOR).secret();

    assertInstanceOf(Ok.class, enrol(OPERATOR, reissued, CHOSEN_PASSWORD));
  }

  @Test
  void reissuingASecretRetiresTheOneBeforeIt() {
    String first = createTheOperator().secret();

    String second = resetThePasswordOf(OPERATOR).secret();

    assertNotEquals(first, second);
    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), enrol(OPERATOR, first, CHOSEN_PASSWORD));
  }

  /** Re-issuing to an Account that never had a password is nothing anybody is owed being told. */
  @Test
  void reissuingBeforeAnyEnrolmentIsNotARecordedReset() throws IOException {
    createTheOperator();

    resetThePasswordOf(OPERATOR);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertFalse(recorded.contains("PASSWORD_RESET_INITIATED"), () -> "recorded: " + recorded);
  }

  @Test
  void aResetIsRecorded() throws IOException {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);

    resetThePasswordOf(OPERATOR);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(recorded.contains("PASSWORD_RESET_INITIATED"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains(OPERATOR), () -> "not recorded against anyone: " + recorded);
  }

  // --- who may ask ----------------------------------------------------------------------------

  @Test
  void anOperatorsSessionCannotCreateAnAccount() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);
    SessionToken operator = admit(OPERATOR, CHOSEN_PASSWORD, Role.OPERATOR);

    Response response = harness.send(new CreateAccount(operator, "rosalind.sanders", Role.OPERATOR));

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR, assertInstanceOf(ErrorResponse.class, response).code());
  }

  @Test
  void anOperatorsSessionCannotResetAPassword() {
    harness.provisionOperator(OPERATOR, CHOSEN_PASSWORD);
    SessionToken operator = admit(OPERATOR, CHOSEN_PASSWORD, Role.OPERATOR);

    Response response = harness.send(new InitiateReset(operator, OPERATOR));

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR, assertInstanceOf(ErrorResponse.class, response).code());
  }

  @Test
  void aTokenThatNamesNoSessionCreatesNothing() {
    Response response =
        harness.send(
            new CreateAccount(SessionToken.generate(new SecureRandom()), OPERATOR, Role.OPERATOR));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
  }

  /** Criterion 11: the one Account nobody else provisions is the one this flow does not touch. */
  @Test
  void theAdministratorIsNeverEnrolledByAnybody() {
    Response response =
        asTheAdministrator(token -> new CreateAccount(token, "gale.ridley", Role.ADMINISTRATOR));

    assertEquals(ErrorCode.CANNOT_ENROL_THE_ADMINISTRATOR, codeOf(response));
  }

  @Test
  void theAdministratorsOwnPasswordCannotBeResetFromASession() {
    assertEquals(
        ErrorCode.CANNOT_ENROL_THE_ADMINISTRATOR,
        codeOf(asTheAdministrator(token -> new InitiateReset(token, ADMINISTRATOR))));
  }

  @Test
  void resettingAnAccountThatDoesNotExistIsRefused() {
    assertEquals(
        ErrorCode.NO_SUCH_ACCOUNT,
        codeOf(asTheAdministrator(token -> new InitiateReset(token, "nobody.here"))));
  }

  /** A name already taken is said plainly, or an Administrator hands out a secret for nothing. */
  @Test
  void creatingAnAccountUnderATakenNameIsRefused() {
    createTheOperator();

    assertEquals(
        ErrorCode.ACCOUNT_EXISTS,
        codeOf(asTheAdministrator(token -> new CreateAccount(token, OPERATOR, Role.OPERATOR))));
  }

  /** Story 14: the naming rules are every Account's, and they are applied in the service. */
  @Test
  void aNameThePolicyRefusesIsRefusedHereToo() {
    Response response =
        asTheAdministrator(token -> new CreateAccount(token, "admin", Role.OPERATOR));

    PolicyRefused refused = assertInstanceOf(PolicyRefused.class, response);
    assertEquals(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED), refused.violations());
  }

  @Test
  void anAccountRefusedByThePolicyIsNotCreated() {
    asTheAdministrator(token -> new CreateAccount(token, "admin", Role.OPERATOR));

    assertEquals(
        Denied.because(DeniedReason.AUTH_FAILED),
        attempt("admin", CHOSEN_PASSWORD),
        "an Account was created for a name the policy refuses");
  }

  // --- getting there ---------------------------------------------------------------------------

  /** Creates the Operator as an Administrator would, and hands the machine back. */
  private EnrolmentIssued createTheOperator() {
    return assertInstanceOf(
        EnrolmentIssued.class,
        asTheAdministrator(token -> new CreateAccount(token, OPERATOR, Role.OPERATOR)));
  }

  private EnrolmentIssued resetThePasswordOf(String accountName) {
    return assertInstanceOf(
        EnrolmentIssued.class, asTheAdministrator(token -> new InitiateReset(token, accountName)));
  }

  /** Logs in as the Administrator, asks what the test came to ask, and logs out again. */
  private Response asTheAdministrator(Function<SessionToken, Request> request) {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    Response response = harness.send(request.apply(administrator));
    logOutOf(administrator);
    return response;
  }

  private Response enrol(String accountName, String secret, String password) {
    return harness.send(
        new CompleteEnrolment(accountName, secret.toCharArray(), password.toCharArray()));
  }

  private Response attempt(String accountName, String password) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), Role.OPERATOR));
  }

  private SessionToken admit(String accountName, String password, Role role) {
    Response response = harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response).token();
  }

  /** A secret this service issued to nobody: what somebody typing at the wrong screen offers. */
  private static String otherSecret() {
    return EnrolmentSecret.generate(new SecureRandom()).text();
  }

  private void logOutOf(SessionToken token) {
    harness.send(new Logout(token));
  }

  private static ErrorCode codeOf(Response response) {
    return assertInstanceOf(ErrorResponse.class, response).code();
  }

  private static String withoutDashes(String secret) {
    return secret.replace("-", "");
  }

  /** Every byte this service wrote anywhere in its own directory, as text to search. */
  private String everythingWrittenToDisk() throws IOException {
    StringBuilder written = new StringBuilder();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        written.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
      }
    }
    return written.toString();
  }

  /**
   * What the store holds, read over a connection of the test's own while the service is running —
   * which is also what makes this an assertion that the write was flushed rather than buffered.
   */
  private String readFromTheStore(String column, String accountName) {
    String storeFile = ServiceHarness.storeFileIn(directory).toString();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        PreparedStatement statement =
            connection.prepareStatement("SELECT " + column + " FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> "there is no Account named " + accountName);
        return results.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
