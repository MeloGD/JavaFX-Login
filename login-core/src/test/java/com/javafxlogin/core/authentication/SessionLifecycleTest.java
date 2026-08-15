package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.harness.StubConnection;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: what starts a Session, what ends it, and what a client is told about either.
 *
 * <p>Everything about the clocks running out is {@link SessionExpiryTest}'s subject. This is the
 * rest of the lifecycle: activity, logging out, the client disappearing, and the machine holding
 * one Session at a time.
 */
class SessionLifecycleTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

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

  @Test
  void anAdmittedOperatorHasALiveSession() {
    SessionToken token = admitAnOperator();

    Response response = harness.send(new AskIfSessionIsLive(token));

    assertInstanceOf(SessionLive.class, response);
  }

  /** Story 46: the countdown starts again, so expiry does not interrupt someone working. */
  @Test
  void activityStartsTheCountdownAgain() {
    SessionToken token = admitAnOperator();
    harness.clock().passes(Duration.ofMinutes(10));

    SessionLive afterActivity = (SessionLive) harness.send(new ReportActivity(token));

    assertEquals(Optional.of(Duration.ofMinutes(15)), afterActivity.expiresIn());
  }

  /** Asking must not be what keeps a Session alive, or an idle client could hold one forever. */
  @Test
  void askingAboutASessionIsNotActivity() {
    SessionToken token = admitAnOperator();
    harness.clock().passes(Duration.ofMinutes(10));

    SessionLive live = (SessionLive) harness.send(new AskIfSessionIsLive(token));
    harness.clock().passes(Duration.ofMinutes(1));
    SessionLive later = (SessionLive) harness.send(new AskIfSessionIsLive(token));

    assertEquals(Optional.of(Duration.ofMinutes(5)), live.expiresIn());
    assertEquals(Optional.of(Duration.ofMinutes(4)), later.expiresIn());
  }

  /** Story 49: a Session ends deliberately when the Operator says so. */
  @Test
  void anOperatorCanLogOut() {
    SessionToken token = admitAnOperator();

    Response loggedOut = harness.send(new Logout(token));

    assertInstanceOf(Ok.class, loggedOut);
    assertEquals(
        SessionEndedReason.NO_SUCH_SESSION, reasonFor(new AskIfSessionIsLive(token)));
  }

  @Test
  void loggingOutTwiceIsNotAnOk() {
    SessionToken token = admitAnOperator();
    harness.send(new Logout(token));

    assertEquals(SessionEndedReason.NO_SUCH_SESSION, reasonFor(new Logout(token)));
  }

  /** Logging out frees the machine, so the next person can be admitted. */
  @Test
  void theMachineIsFreeOnceSomeoneHasLoggedOut() {
    SessionToken token = admitAnOperator();
    harness.send(new Logout(token));

    assertInstanceOf(Granted.class, authenticate(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));
  }

  /**
   * Story 50 and ADR-0003: a Session is bound to its connection, and the kernel closes that
   * connection for a client that dies. No heartbeat takes part in this, and nothing waits for one.
   */
  @Test
  void aSessionEndsWithTheConnectionItWasGrantedOn() {
    admitAnOperator();

    harness.connection().close();

    StubConnection afterTheCrash = harness.anotherConnection();
    assertInstanceOf(
        Granted.class,
        harness.sendOver(
            afterTheCrash,
            new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR)));
  }

  /** Story 54: one Session on the machine, and it is the one already open that is kept. */
  @Test
  void aSecondAuthenticationIsRefusedWhileASessionIsLive() {
    SessionToken token = admitAnOperator();

    Response response =
        harness.sendOver(
            harness.anotherConnection(),
            new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));

    Denied denied = assertInstanceOf(Denied.class, response);
    assertEquals(DeniedReason.SESSION_ALREADY_LIVE, denied.reason());
    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  /**
   * The refusal is decided before any Account is read, so a wrong password produces exactly the
   * same answer. Were it otherwise, a second attempt would be an oracle for whether a password was
   * right at a moment when nobody is entitled to ask.
   */
  @Test
  void theRefusalIsMadeWithoutLookingAtAnyAccount() {
    admitAnOperator();
    StubConnection second = harness.anotherConnection();

    Response withTheRightPassword =
        harness.sendOver(
            second, new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));
    Response withTheWrongOne =
        harness.sendOver(
            second, new Authenticate(OPERATOR, "Wrong-Horse-9".toCharArray(), Role.OPERATOR));
    Response forAnAccountThatDoesNotExist =
        harness.sendOver(
            second, new Authenticate("nobody.here", "Wrong-Horse-9".toCharArray(), Role.OPERATOR));

    assertEquals(withTheRightPassword, withTheWrongOne);
    assertEquals(withTheRightPassword, forAnAccountThatDoesNotExist);
  }

  /** An Administrator's Session holds the machine too: there is one Session, not one per Role. */
  @Test
  void anAdministratorsSessionAlsoHoldsTheMachine() {
    authenticate(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    Response response =
        harness.sendOver(
            harness.anotherConnection(),
            new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));

    assertEquals(new Denied(DeniedReason.SESSION_ALREADY_LIVE), response);
  }

  /**
   * A token is not a bearer credential to be replayed from somewhere else. The Session belongs to
   * the connection it was granted on, and a copy of the token presented on another one names
   * nothing.
   */
  @Test
  void aTokenPresentedOnAnotherConnectionNamesNoSession() {
    SessionToken token = admitAnOperator();

    Response response =
        harness.sendOver(harness.anotherConnection(), new AskIfSessionIsLive(token));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(token)));
  }

  @Test
  void aTokenThisServiceNeverIssuedNamesNoSession() {
    admitAnOperator();

    Response response =
        harness.send(new AskIfSessionIsLive(SessionToken.generate(new SecureRandom())));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
  }

  private SessionToken admitAnOperator() {
    return ((Granted) authenticate(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR)).token();
  }

  private Response authenticate(String accountName, String password, Role requestedRole) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), requestedRole));
  }

  private SessionEndedReason reasonFor(Request request) {
    return assertInstanceOf(SessionEnded.class, harness.send(request)).reason();
  }
}
