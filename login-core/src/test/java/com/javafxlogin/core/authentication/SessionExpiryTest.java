package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: when the two clocks say a Session is over.
 *
 * <p>Neither clock alone would do. The monotonic one cannot be moved, which is what makes setting
 * the machine's time buy nothing; the wall clock counts the time the machine spent suspended, which
 * the monotonic one does not. Expiry runs against whichever says more time passed, and a
 * disagreement between them larger than the service tolerates ends the Session on its own.
 */
class SessionExpiryTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  private static final Duration PERIOD = Duration.ofMinutes(15);

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithAnOperator() {
    harness = ServiceHarness.cheap(directory);
    harness.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    harness.provisionOperator(OPERATOR, OPERATOR_PASSWORD);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  /** Story 45: walking away does not leave the ProtectedFeature open. */
  @Test
  void aSessionEndsAfterThePeriodWithoutActivity() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(PERIOD);

    assertEquals(SessionEndedReason.INACTIVITY, reasonFor(token));
  }

  @Test
  void aSessionSurvivesUpToTheLastMomentOfThePeriod() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(PERIOD.minusSeconds(1));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  /** Story 46: the countdown is measured from the last activity, not from the admission. */
  @Test
  void activityBuysAnotherWholePeriod() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(Duration.ofMinutes(14));
    harness.send(new ReportActivity(token));
    harness.clock().passes(Duration.ofMinutes(14));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  /**
   * Story 52, the half the monotonic clock answers: a clock set backwards does not lengthen a
   * Session, because expiry runs against whichever clock says more time passed and that one cannot
   * be moved. Kept inside the tolerance so that what is being asserted is the measure rather than
   * the disagreement.
   */
  @Test
  void aClockSetBackwardsDoesNotLengthenASession() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(PERIOD);
    harness.clock().theWallClockJumps(Duration.ofSeconds(-45));

    assertEquals(SessionEndedReason.INACTIVITY, reasonFor(token));
  }

  /**
   * Story 52, the half the wall clock answers: time the monotonic clock did not count still counts
   * against the Session. Again inside the tolerance, so this is the measure and not the jump.
   */
  @Test
  void timeTheMonotonicClockDidNotCountStillCountsAgainstTheSession() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(PERIOD.minusSeconds(30));
    harness.clock().theWallClockJumps(Duration.ofSeconds(45));

    assertEquals(SessionEndedReason.INACTIVITY, reasonFor(token));
  }

  /** Story 53: a clock that moved further than the service tolerates ends the Session. */
  @Test
  void aWallClockJumpBeyondToleranceEndsTheSession() {
    SessionToken token = admitAnOperator();

    harness.clock().theWallClockJumps(Duration.ofHours(2));

    assertEquals(SessionEndedReason.CLOCK_JUMPED, reasonFor(token));
  }

  @Test
  void aWallClockJumpBackwardsBeyondToleranceEndsTheSessionToo() {
    SessionToken token = admitAnOperator();

    harness.clock().theWallClockJumps(Duration.ofHours(-2));

    assertEquals(SessionEndedReason.CLOCK_JUMPED, reasonFor(token));
  }

  /** Ordinary drift, and the correction that follows it, are not a clock jump. */
  @Test
  void aCorrectionSmallerThanTheToleranceIsNotAJump() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(Duration.ofMinutes(5));
    harness.clock().theWallClockJumps(Duration.ofSeconds(30));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  /** Story 53: neither useful nor invisible. */
  @Test
  void aClockJumpIsRecordedAsAnAuthenticationEvent() throws IOException {
    SessionToken token = admitAnOperator();

    harness.clock().theWallClockJumps(Duration.ofHours(2));
    reasonFor(token);

    String recorded = recordedEvents();
    assertTrue(
        recorded.contains("SESSION_ENDED_BY_A_CLOCK_JUMP"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains(OPERATOR), () -> "not recorded against anyone: " + recorded);
  }

  /** The record is of the anomaly. Someone going to lunch is not one, and does not fill a disk. */
  @Test
  void anOrdinaryTimeoutIsNotRecorded() throws IOException {
    SessionToken token = admitAnOperator();
    String beforeTheTimeout = recordedEvents();

    harness.clock().passes(PERIOD);
    reasonFor(token);

    assertEquals(beforeTheTimeout, recordedEvents());
  }

  /** The SessionToken is never written down, and an event about a Session is still not a place. */
  @Test
  void nothingRecordedAboutASessionCarriesItsToken() throws IOException {
    SessionToken token = admitAnOperator();

    harness.clock().theWallClockJumps(Duration.ofHours(2));
    reasonFor(token);

    String asItCrossesTheWire = Base64.getEncoder().encodeToString(token.copyOfBytes());
    assertFalse(recordedEvents().contains(asItCrossesTheWire), "the token reached the record");
  }

  /** Stories 47 and 48: a kiosk keeps its Session, however long nobody touches it. */
  @Test
  void aSessionDoesNotExpireWhereExpiryIsSwitchedOff() {
    harness.inactivityPeriodIs(InactivityPeriod.disabled());
    SessionToken token = admitAnOperator();

    harness.clock().passes(Duration.ofDays(3));

    SessionLive live =
        assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
    assertTrue(live.expiresIn().isEmpty(), "a kiosk Session has nothing to count down to");
  }

  /** A clock jump on a kiosk is not an expiry either: no idle time is being accounted for. */
  @Test
  void aClockJumpDoesNotEndAKioskSession() {
    harness.inactivityPeriodIs(InactivityPeriod.disabled());
    SessionToken token = admitAnOperator();

    harness.clock().theWallClockJumps(Duration.ofHours(2));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  /** An expired Session leaves the machine free, which is what lets the next person in. */
  @Test
  void anExpiredSessionNoLongerHoldsTheMachine() {
    admitAnOperator();

    harness.clock().passes(PERIOD);

    assertInstanceOf(
        Granted.class,
        harness.send(new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR)));
  }

  /**
   * Expiry is decided when something asks, and the client that comes back to a Session that ran out
   * while it was not looking is told what happened rather than that its token means nothing.
   */
  @Test
  void aClientIsToldWhyTheSessionItHeldIsGone() {
    SessionToken token = admitAnOperator();

    harness.clock().passes(PERIOD);
    SessionEndedReason whenItWasNoticed = reasonFor(token);
    SessionEndedReason askedAgain = reasonFor(token);

    assertEquals(SessionEndedReason.INACTIVITY, whenItWasNoticed);
    assertEquals(SessionEndedReason.INACTIVITY, askedAgain);
  }

  private SessionToken admitAnOperator() {
    Response response =
        harness.send(new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));
    return assertInstanceOf(Granted.class, response).token();
  }

  private SessionEndedReason reasonFor(SessionToken token) {
    return assertInstanceOf(SessionEnded.class, harness.send(new AskIfSessionIsLive(token)))
        .reason();
  }

  private String recordedEvents() throws IOException {
    Path log = ServiceHarness.eventLogIn(directory);
    return Files.exists(log) ? Files.readString(log) : "";
  }
}
