package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionEndedReason;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: the window an admitted Operator works in, driven by TestFX on Monocle with no display,
 * against a fake LoginGate.
 *
 * <p>What is asserted here is the window's behaviour: that the Operator's activity is reported,
 * that the window closes and hands the person back when the service says the Session is over, and
 * that logging out does the same deliberately. <em>When</em> a Session is over is the
 * AuthenticationService's decision and is tested where that decision is made — nothing here waits
 * out an inactivity period, because nothing here would be asserting about one.
 */
class SessionWindowTest extends ApplicationTest {

  private static final String OPERATOR = "finch.mercer";
  private static final String PASSWORD = "Another-Horse-2";

  private static final int PATIENCE_IN_SECONDS = 10;

  /** Short enough that the guard's next question arrives inside a test's patience. */
  private static final Duration SOON = Duration.ofMillis(100);

  private FakeLoginGate gate;
  private Stage loginStage;

  @Override
  public void start(Stage stage) {
    loginStage = stage;
    gate = new FakeLoginGate().admitting(OPERATOR, PASSWORD);
    gate.protect(stage, this::protectedFeature);
  }

  /** The host product's view, which the gate is handed and knows nothing else about. */
  private Parent protectedFeature(Session session) {
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }

  @Test
  void theHostsViewOpensInsideTheWindowTheGateOwns() {
    admitAnOperator();

    assertTrue(lookup("#feature").tryQuery().isPresent(), "the host's view should be inside");
    assertTrue(lookup("#logOut").tryQuery().isPresent(), "there should be a way to log out");
  }

  /** CONTEXT.md: the SessionGuard reports Operator activity. This is the reporting. */
  @Test
  void whatTheOperatorDoesIsReportedToTheService() {
    admitAnOperator();
    assertEquals(0, gate.activityReports(), "nothing has been done in the window yet");

    clickOn("#feature");

    await(() -> gate.activityReports() >= 1);
  }

  /** Story 45: the window closes and the person is back at the login screen. */
  @Test
  void theWindowClosesAndTheLoginScreenReturnsWhenTheSessionEnds() {
    gate.sessionsLastFor(SOON);
    admitAnOperator();

    gate.theSessionEnds(SessionEndedReason.INACTIVITY);

    awaitTheLoginScreen();
    // Awaited rather than asserted: the login screen is shown and the window it replaces is closed
    // in that order, on purpose, so for one moment both are on the screen.
    await(() -> lookup("#feature").tryQuery().isEmpty());
  }

  @Test
  void theLoginScreenSaysWhyTheSessionEnded() {
    gate.sessionsLastFor(SOON);
    admitAnOperator();

    gate.theSessionEnds(SessionEndedReason.CLOCK_JUMPED);

    awaitTheLoginScreen();
    await(() -> !message().isBlank());
    assertNotEquals(
        SessionEndedText.sentenceFor(SessionEndedReason.INACTIVITY),
        message(),
        "one reason must not be worded as another");
  }

  /** The screen someone comes back to is a fresh one, not the one they typed into an hour ago. */
  @Test
  void theLoginScreenSomeoneComesBackToIsEmpty() {
    gate.sessionsLastFor(SOON);
    admitAnOperator();

    gate.theSessionEnds(SessionEndedReason.INACTIVITY);

    awaitTheLoginScreen();
    await(() -> textOf("#accountName").isEmpty());
    assertEquals("", textOf("#password"));
  }

  /** Story 49: ending it deliberately ends it at the service, not merely in the window. */
  @Test
  void loggingOutEndsTheSessionAndHandsThePersonBack() {
    admitAnOperator();

    clickOn("#logOut");

    awaitTheLoginScreen();
    assertEquals(1, gate.logouts(), "the service should have been told");
    await(() -> message().equals(SessionEndedText.LOGGED_OUT));
  }

  /**
   * A service that went away mid-Session is not something to leave a person working through: the
   * window they are in is no longer being watched by anything.
   */
  @Test
  void aServiceThatGoesAwayMidSessionHandsThePersonBackToo() {
    gate.sessionsLastFor(SOON);
    admitAnOperator();

    gate.becomeUnreachable();

    awaitTheLoginScreen();
    await(() -> message().equals(SessionEndedText.SERVICE_LOST));
  }

  /** Story 48: a kiosk Session has nothing to count down to, so the guard has nothing to ask. */
  @Test
  void aSessionThatNeverExpiresIsNotAskedAboutAgain() {
    gate.sessionsNeverExpire();
    admitAnOperator();

    await(() -> gate.questionsAboutTheSession() == 1);
    sleepBriefly();

    assertEquals(1, gate.questionsAboutTheSession(), "a kiosk Session should not be polled");
    assertTrue(lookup("#feature").tryQuery().isPresent(), "the feature should still be open");
  }

  // --- driving the windows -----------------------------------------------------------------

  /**
   * Story 29: an Operator whose password an Administrator reset is told so, and told when. This is
   * the first window they see afterwards — the login screen the service said it on has already
   * closed — so if it is not said here it is not said at all.
   */
  @Test
  void anOperatorIsToldTheirPasswordWasResetAndWhen() {
    Instant resetAt = Instant.parse("2026-03-01T09:00:00Z");
    gate.withAPasswordResetAt(resetAt);

    admitAnOperator();

    String notice = lookup("#notice").queryAs(Label.class).getText();
    assertFalse(notice.isBlank(), "nothing said about a reset the Operator did not ask for");
    // Written as this machine writes a moment rather than as ISO-8601, which is the audit log's
    // format and is read by tools. This one is read by a person over their own shoulder.
    assertTrue(
        notice.contains(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(resetAt)),
        () -> "no moment to read in: " + notice);
  }

  /** And an ordinary login says nothing, rather than a banner nobody has a reason to read. */
  @Test
  void anOrdinaryLoginIsToldNothing() {
    admitAnOperator();

    assertEquals("", lookup("#notice").queryAs(Label.class).getText());
    assertFalse(
        lookup("#noticeRead").queryAs(Button.class).isVisible(),
        "there is nothing to say and something to dismiss it with");
  }

  /**
   * The notice is spent when the person reads it and not when the service sends it. Dismissing it is
   * what tells the service, which until then says it again at every admission — so a client that
   * died before drawing this window has not swallowed the only copy.
   */
  @Test
  void theNoticeIsOverOnlyWhenThePersonSaysTheyHaveReadIt() {
    gate.withAPasswordResetAt(Instant.parse("2026-03-01T09:00:00Z"));
    admitAnOperator();
    assertEquals(0, gate.noticesRead(), "nobody has read anything yet");

    clickOn("#noticeRead");

    await(() -> gate.noticesRead() == 1);
    assertEquals("", lookup("#notice").queryAs(Label.class).getText(), "the notice should be gone");
    assertFalse(lookup("#noticeRead").queryAs(Button.class).isVisible());
  }

  /**
   * A service that could not be told is not something to put in front of the person. They have read
   * it; the worst it costs is being shown it again next time, which is the safe direction.
   */
  @Test
  void aNoticeStaysDismissedEvenWhenTheServiceCannotBeTold() {
    gate.withAPasswordResetAt(Instant.parse("2026-03-01T09:00:00Z"));
    admitAnOperator();

    gate.cannotBeToldTheNoticeWasRead();
    clickOn("#noticeRead");

    await(() -> gate.noticesRead() == 1);
    assertEquals("", lookup("#notice").queryAs(Label.class).getText(), "the notice should be gone");
    assertTrue(lookup("#feature").tryQuery().isPresent(), "the window should still be here");
  }

  private void admitAnOperator() {
    clickOn("#accountName").write(OPERATOR);
    clickOn("#password").write(PASSWORD);
    clickOn("#admit");
    await(() -> lookup("#feature").tryQuery().isPresent());
  }

  private void awaitTheLoginScreen() {
    await(loginStage::isShowing);
  }

  private void sleepBriefly() {
    WaitForAsyncUtils.sleep(SOON.toMillis() * 5, TimeUnit.MILLISECONDS);
  }

  private void await(BooleanSupplier condition) {
    try {
      WaitForAsyncUtils.waitFor(PATIENCE_IN_SECONDS, TimeUnit.SECONDS, condition::getAsBoolean);
    } catch (TimeoutException e) {
      throw new AssertionError("the window never got there; it said: " + message(), e);
    }
  }

  private String message() {
    return lookup("#message").queryAs(Label.class).getText();
  }

  private String textOf(String query) {
    return lookup(query).queryAs(TextInputControl.class).getText();
  }
}
