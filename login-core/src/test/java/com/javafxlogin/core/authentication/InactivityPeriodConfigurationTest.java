package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.ChangeInactivityPeriod;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the Administrator configures how long a Session may idle, and nobody else does.
 *
 * <p>Stories 47 and 48. The screen an Administrator does this from is the administration panel's
 * ticket; what is settled here is the decision underneath it, which is the half a patched client
 * cannot talk its way past.
 */
class InactivityPeriodConfigurationTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  private static final InactivityPeriod ONE_MINUTE =
      InactivityPeriod.of(Duration.ofMinutes(1));

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

  /** Story 47: the period is a property of the deployment, and the next Session runs under it. */
  @Test
  void whatTheAdministratorConfiguresIsWhatTheNextSessionExpiresBy() {
    changeTo(ONE_MINUTE);
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    harness.clock().passes(Duration.ofMinutes(1));

    assertEquals(
        new SessionEnded(SessionEndedReason.INACTIVITY),
        harness.send(new AskIfSessionIsLive(operator)));
  }

  /** Story 48: a kiosk deployment is one where an Administrator switched expiry off. */
  @Test
  void expiryCanBeSwitchedOffEntirely() {
    changeTo(InactivityPeriod.disabled());
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    harness.clock().passes(Duration.ofDays(1));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(operator)));
  }

  /** The change outlives the Session that made it, and the service that was running at the time. */
  @Test
  void theChangeSurvivesAServiceRestart() {
    changeTo(ONE_MINUTE);

    harness.restart();
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);
    harness.clock().passes(Duration.ofMinutes(1));

    assertInstanceOf(SessionEnded.class, harness.send(new AskIfSessionIsLive(operator)));
  }

  /**
   * The Role checked is the one the Session was granted in, which the service decided when it
   * verified a password. An Operator asking is refused here, in the privileged process.
   */
  @Test
  void anOperatorsSessionCannotConfigureTheDeployment() {
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    Response response = harness.send(new ChangeInactivityPeriod(operator, ONE_MINUTE));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_ADMINISTRATOR, error.code());
  }

  @Test
  void anOperatorRefusedTheChangeChangesNothing() {
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    harness.send(new ChangeInactivityPeriod(operator, ONE_MINUTE));
    harness.clock().passes(Duration.ofMinutes(1));

    assertInstanceOf(SessionLive.class, harness.send(new AskIfSessionIsLive(operator)));
  }

  @Test
  void aTokenThatNamesNoSessionConfiguresNothing() {
    Response response =
        harness.send(
            new ChangeInactivityPeriod(SessionToken.generate(new SecureRandom()), ONE_MINUTE));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
  }

  /** A configuration change is one of the things CONTEXT.md says an AuthenticationEvent is. */
  @Test
  void aChangeIsRecordedAgainstTheAdministratorWhoMadeIt() throws IOException {
    changeTo(ONE_MINUTE);

    String recorded = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(recorded.contains("CONFIGURATION_CHANGED"), () -> "not recorded: " + recorded);
    assertTrue(recorded.contains(ADMINISTRATOR), () -> "not recorded against anyone: " + recorded);
  }

  /** Configures the deployment as the Administrator would, and hands the machine back. */
  private void changeTo(InactivityPeriod period) {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    assertInstanceOf(
        Ok.class, harness.send(new ChangeInactivityPeriod(administrator, period)));

    harness.send(new Logout(administrator));
  }

  private SessionToken admit(String accountName, String password, Role role) {
    Response response =
        harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response).token();
  }
}
