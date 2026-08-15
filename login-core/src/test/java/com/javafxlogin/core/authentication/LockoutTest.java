package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.ClearLockout;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: an Account that fails authentication often enough is refused for a while, and stopping
 * the service is not a way out of it.
 *
 * <p>Stories 40 to 44. What is asserted here is where the state lives as much as what it does: the
 * service stops after five idle minutes, so a Lockout that did not survive that would be one an
 * attacker clears by waiting.
 */
class LockoutTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final String WRONG_PASSWORD = "Wrong-Horse-9";

  /** What V004 writes, and what every test here that does not say otherwise runs against. */
  private static final int FAILURES_THAT_LOCK = 5;

  private static final Duration LOCKOUT_LASTS_FOR = Duration.ofMinutes(15);

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithBothRoles() {
    harness = ServiceHarness.cheap(directory);
    harness.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    harness.provisionOperator(OPERATOR, OPERATOR_PASSWORD);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  /** Story 40: guessing at the login screen stops being free after the configured number. */
  @Test
  void theConfiguredNumberOfFailuresLocksTheAccount() {
    for (int attempt = 1; attempt < FAILURES_THAT_LOCK; attempt++) {
      assertEquals(
          Denied.because(DeniedReason.AUTH_FAILED),
          attempt(OPERATOR, WRONG_PASSWORD),
          "attempt " + attempt + " should have been an ordinary refusal");
    }

    assertEquals(Denied.lockedFor(LOCKOUT_LASTS_FOR), attempt(OPERATOR, WRONG_PASSWORD));
  }

  /**
   * Story 43: the failure that locks says so, rather than reading as one more wrong password. Being
   * told only that it failed is what keeps a person guessing at an Account that has stopped
   * listening.
   */
  @Test
  void aLockedAccountIsToldSoAndForHowLong() {
    lockOutTheOperator();

    Denied denied = assertInstanceOf(Denied.class, attempt(OPERATOR, WRONG_PASSWORD));

    assertEquals(DeniedReason.LOCKED_OUT, denied.reason());
    assertEquals(Optional.of(LOCKOUT_LASTS_FOR), denied.lockedFor());
  }

  /** The countdown is real: what is left shrinks as the machine's clock moves. */
  @Test
  void theRefusalSaysWhatIsLeftOfTheLockoutRatherThanItsWholeLength() {
    lockOutTheOperator();

    harness.clock().passes(Duration.ofMinutes(5));

    assertEquals(Denied.lockedFor(Duration.ofMinutes(10)), attempt(OPERATOR, WRONG_PASSWORD));
  }

  /** The right password is no way past a Lockout — that is the whole of what a Lockout is. */
  @Test
  void aLockedAccountIsRefusedEvenWithTheRightPassword() {
    lockOutTheOperator();

    assertEquals(Denied.lockedFor(LOCKOUT_LASTS_FOR), attempt(OPERATOR, OPERATOR_PASSWORD));
  }

  /** Story 41: stopping the service, which it does by itself after five idle minutes, clears it. */
  @Test
  void theLockoutSurvivesARestartOfTheService() {
    lockOutTheOperator();

    harness.restart();

    assertEquals(Denied.lockedFor(LOCKOUT_LASTS_FOR), attempt(OPERATOR, OPERATOR_PASSWORD));
  }

  @Test
  void theLockoutEndsWhenItsTimeHasPassed() {
    lockOutTheOperator();

    harness.clock().passes(LOCKOUT_LASTS_FOR);

    assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD));
  }

  /**
   * A Lockout that has run out leaves nothing behind. Were the count kept, the first wrong password
   * after the wait would be the one that locks the Account straight back for another quarter of an
   * hour.
   */
  @Test
  void theCountStartsAgainWhenTheLockoutIsOver() {
    lockOutTheOperator();
    harness.clock().passes(LOCKOUT_LASTS_FOR);

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), attempt(OPERATOR, WRONG_PASSWORD));
  }

  /**
   * The last acceptance criterion: authenticating forgets what came before it. Someone who mistypes
   * their password four mornings running and gets it right each time is never four fifths of the
   * way to being locked out.
   */
  @Test
  void aSuccessfulAuthenticationForgetsTheFailuresBeforeIt() {
    failTimes(FAILURES_THAT_LOCK - 1);

    logOutOf(assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD)));

    assertEquals(
        0,
        failuresRecordedFor(OPERATOR),
        "the failures from before the successful attempt were still being counted");
  }

  /** And the failures after it start from none, rather than from where the last run left off. */
  @Test
  void theFailuresBeforeASuccessfulAttemptNeverAddUpToALockout() {
    failTimes(FAILURES_THAT_LOCK - 1);
    logOutOf(assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD)));

    failTimes(FAILURES_THAT_LOCK - 1);

    assertEquals(FAILURES_THAT_LOCK - 1, failuresRecordedFor(OPERATOR));
  }

  /**
   * A clock set backwards must not turn a quarter of an hour into a year. Whoever can move the
   * machine's clock is a MachineAdministrator, who can read and rewrite this file directly, so
   * reading a Lockout that ends further away than one is allowed to last as over costs nothing that
   * was not already gone — and refusing a person until a date a clock error invented would be this
   * system locking someone out of their own product.
   */
  @Test
  void aLockoutNeverOutlastsTheTimeItWasConfiguredFor() {
    lockOutTheOperator();

    harness.clock().theWallClockJumps(Duration.ofDays(-365));

    assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD));
  }

  /** The number is configuration read from the store, not a constant compiled into the service. */
  @Test
  void theNumberOfFailuresThatLocksIsWhateverTheStoreSays() {
    harness.lockoutPolicyIs(2, Duration.ofMinutes(30));

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), attempt(OPERATOR, WRONG_PASSWORD));
    assertEquals(Denied.lockedFor(Duration.ofMinutes(30)), attempt(OPERATOR, WRONG_PASSWORD));
  }

  /**
   * An Administrator authenticating at the login screen offers the right password in the wrong
   * Role, and is refused in the same words as a wrong password. It counts against them like any
   * other failure: an Account that could never be locked out would be one an attacker could pick
   * out of the account list by failing at it all afternoon.
   */
  @Test
  void aRightPasswordInTheWrongRoleCountsTowardsTheLockout() {
    for (int attempt = 1; attempt < FAILURES_THAT_LOCK; attempt++) {
      assertEquals(
          Denied.because(DeniedReason.AUTH_FAILED),
          attempt(ADMINISTRATOR, ADMINISTRATOR_PASSWORD),
          "attempt " + attempt + " should have been an ordinary refusal");
    }

    assertEquals(
        Denied.lockedFor(LOCKOUT_LASTS_FOR), attempt(ADMINISTRATOR, ADMINISTRATOR_PASSWORD));
  }

  /**
   * A name nobody holds is refused and forgotten, however often it is offered. Remembering it would
   * mean a row in the CredentialStore for every string ever typed at the login screen, and one of
   * those strings is eventually somebody's password typed into the wrong box.
   */
  @Test
  void aNameNoAccountHoldsIsNeverLockedOutAndIsNeverWrittenDown() throws IOException {
    for (int attempt = 0; attempt < FAILURES_THAT_LOCK + 3; attempt++) {
      assertEquals(
          Denied.because(DeniedReason.AUTH_FAILED), attempt("mallory.quill", WRONG_PASSWORD));
    }

    String store =
        new String(
            Files.readAllBytes(ServiceHarness.storeFileIn(directory)), StandardCharsets.ISO_8859_1);
    assertFalse(store.contains("mallory.quill"), "a name nobody holds was written to the store");
  }

  /**
   * The refusal before the last one is the refusal an absent Account gets, byte for byte. Only the
   * one that locks says more, which is the trade ADR-0010 records.
   */
  @Test
  void untilItLocksTheRefusalIsTheOneAnAbsentAccountGets() {
    assertEquals(attempt("nobody.here", WRONG_PASSWORD), attempt(OPERATOR, WRONG_PASSWORD));
  }

  /** Story 42 and the criterion behind it: the state is in the file only the service can read. */
  @Test
  void theLockoutIsWrittenToTheStoreAtOnceRatherThanWhenTheServiceStops() {
    lockOutTheOperator();

    assertNotEquals(
        Optional.empty(),
        refusedUntilRecordedFor(OPERATOR),
        "the Lockout was still only in the service's memory");
  }

  /** Story 44: an Administrator releases a colleague who fat-fingered their password. */
  @Test
  void anAdministratorCanClearALockout() {
    lockOutTheOperator();

    clearTheLockoutOf(OPERATOR);

    assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD));
  }

  /** Clearing forgets the count as well as the refusal, or the next slip would lock it again. */
  @Test
  void clearingALockoutLeavesNothingCountedAgainstTheAccount() {
    lockOutTheOperator();
    clearTheLockoutOf(OPERATOR);

    assertEquals(Denied.because(DeniedReason.AUTH_FAILED), attempt(OPERATOR, WRONG_PASSWORD));
  }

  @Test
  void anOperatorsSessionCannotClearALockout() {
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    Response response = harness.send(new ClearLockout(operator, OPERATOR));

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR, assertInstanceOf(ErrorResponse.class, response).code());
  }

  /** A mistyped name must not read as success, or the colleague stays locked out. */
  @Test
  void clearingALockoutForAnAccountThatDoesNotExistIsRefused() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    Response response = harness.send(new ClearLockout(administrator, "finch.mercerr"));

    assertEquals(ErrorCode.NO_SUCH_ACCOUNT, assertInstanceOf(ErrorResponse.class, response).code());
  }

  /** Clearing an Account that is not locked is what was asked for, not a failure to do it. */
  @Test
  void clearingAnAccountThatIsNotLockedIsStillOk() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    assertInstanceOf(Ok.class, harness.send(new ClearLockout(administrator, OPERATOR)));
  }

  @Test
  void aTokenThatNamesNoSessionClearsNothing() {
    Response response =
        harness.send(new ClearLockout(SessionToken.generate(new SecureRandom()), OPERATOR));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
  }

  /** CONTEXT.md: a lockout is one of the things an AuthenticationEvent records. */
  @Test
  void enteringALockoutIsRecorded() throws IOException {
    lockOutTheOperator();

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(recorded.contains("ACCOUNT_LOCKED_OUT"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains(OPERATOR), () -> "not recorded against anyone: " + recorded);
  }

  @Test
  void clearingALockoutIsRecorded() throws IOException {
    lockOutTheOperator();

    clearTheLockoutOf(OPERATOR);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(recorded.contains("LOCKOUT_CLEARED"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains(OPERATOR), () -> "not recorded against anyone: " + recorded);
  }

  /** Every refusal counts once, so an Account is not locked out sooner than it was told it is. */
  @Test
  void oneAttemptCountsOnce() {
    failTimes(FAILURES_THAT_LOCK - 1);

    assertEquals(FAILURES_THAT_LOCK - 1, failuresRecordedFor(OPERATOR));
  }

  // --- getting there -----------------------------------------------------------------------

  private void lockOutTheOperator() {
    failTimes(FAILURES_THAT_LOCK - 1);
    assertEquals(Denied.lockedFor(LOCKOUT_LASTS_FOR), attempt(OPERATOR, WRONG_PASSWORD));
  }

  private void failTimes(int times) {
    for (int attempt = 0; attempt < times; attempt++) {
      assertEquals(Denied.because(DeniedReason.AUTH_FAILED), attempt(OPERATOR, WRONG_PASSWORD));
    }
  }

  /** Clears a Lockout as the Administrator would, and hands the machine back. */
  private void clearTheLockoutOf(String accountName) {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    assertInstanceOf(Ok.class, harness.send(new ClearLockout(administrator, accountName)));

    logOutOf(administrator);
  }

  private Response attempt(String accountName, String password) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), Role.OPERATOR));
  }

  private SessionToken admit(String accountName, String password, Role role) {
    Response response = harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response).token();
  }

  private void logOutOf(Granted granted) {
    logOutOf(granted.token());
  }

  private void logOutOf(SessionToken token) {
    harness.send(new Logout(token));
  }

  // --- reading the store from outside the service --------------------------------------------

  /**
   * What the store holds, read over a connection of the test's own while the service is still
   * running. A row that is only in the service's memory is not visible here, which is what makes
   * this the assertion that the write was flushed rather than buffered.
   */
  private Optional<String> refusedUntilRecordedFor(String accountName) {
    return Optional.ofNullable(readFromTheStore("refused_until", accountName));
  }

  private int failuresRecordedFor(String accountName) {
    return Integer.parseInt(readFromTheStore("failed_authentications", accountName));
  }

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
