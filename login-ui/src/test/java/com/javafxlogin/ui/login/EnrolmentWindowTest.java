package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.Session;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: the screen where somebody turns the code they were handed into a password of their own,
 * driven by TestFX on Monocle with no display against a fake LoginGate.
 *
 * <p>Story 23, and story 30 as a person meets it. Whether a code is the right one is the
 * AuthenticationService's decision and is tested where that decision is made; what is asserted here
 * is where the person ends up, what the screen says, and what it refuses to say.
 */
class EnrolmentWindowTest extends ApplicationTest {

  /**
   * The language every window in this test is drawn in, named rather than taken from the machine
   * the suite happens to be running on: what a screen says is asserted against the bundle it came
   * from, and a developer's locale is not a thing to assert against.
   */
  private static final InterfaceLanguage SPANISH =
      InterfaceLanguage.of(Locale.forLanguageTag("es"));

  private static final String NEWCOMER = "rosalind.sanders";
  private static final String SECRET = "K7QF-9M2X-3WBR-8ZDN-5YCG-VJH2-P4";
  private static final String CHOSEN = "Another-Horse-2";

  private static final int PATIENCE_IN_SECONDS = 10;

  private final AtomicInteger featuresBuilt = new AtomicInteger();

  private FakeLoginGate gate;

  @Override
  public void start(Stage stage) {
    gate = new FakeLoginGate().awaitingEnrolment(NEWCOMER);
    GateFlow.open(gate, stage, this::protectedFeature, SPANISH);
  }

  private Parent protectedFeature(Session session) {
    featuresBuilt.incrementAndGet();
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }

  /**
   * Story 30 as a person meets it: an Account with no password is not a wrong password, and being
   * told to try another one would be being told to fix something that cannot be fixed there.
   */
  @Test
  void anAccountAwaitingEnrolmentSendsThePersonToTheEnrolmentScreen() {
    attemptToLogIn(NEWCOMER, "Nothing-Yet-1");

    awaitTheEnrolmentScreen();
    assertEquals(NEWCOMER, textOf("#accountName"), "the name should have come with them");
  }

  /** And the other way in, for somebody who has a code and has never had a password to try. */
  @Test
  void thereIsAWayToTheEnrolmentScreenWithoutBeingSentThere() {
    clickOn("#enrolInstead");

    awaitTheEnrolmentScreen();
  }

  /**
   * The code is copied off something else, character by character. Hiding it behind dots is how a
   * transcription error becomes three failed attempts and a Lockout.
   */
  @Test
  void theCodeIsShownAsItIsTyped() {
    goToTheEnrolmentScreen();

    assertFalse(
        lookup("#secret").query() instanceof PasswordField,
        "the one-time code is being hidden from the person copying it");
    assertTrue(lookup("#password").query() instanceof PasswordField, "the password is not hidden");
  }

  /** Story 23: the password is chosen here, and the login screen takes it immediately. */
  @Test
  void enrollingHandsThePersonBackToTheLoginScreenWithAPasswordThatWorks() {
    goToTheEnrolmentScreen();

    enrol(SECRET, CHOSEN, CHOSEN);

    awaitTheLoginScreen();
    assertFalse(message().isBlank(), "the login screen should say the enrolment worked");
    attemptToLogIn(NEWCOMER, CHOSEN);
    awaitTheProtectedFeature();
  }

  @Test
  void offersTheGateExactlyWhatWasTyped() {
    goToTheEnrolmentScreen();

    enrol(SECRET, CHOSEN, CHOSEN);

    awaitTheLoginScreen();
    assertEquals(List.of(NEWCOMER + "/" + SECRET + "/" + CHOSEN), gate.enrolments());
  }

  /**
   * The screen's own rule, and the only one it has. Nothing is sent: a password typed differently
   * twice is not a question the privileged process has any business answering.
   */
  @Test
  void twoPasswordsThatDoNotMatchAreRefusedWithoutAskingTheService() {
    goToTheEnrolmentScreen();

    enrol(SECRET, CHOSEN, "Another-Horse-3");

    awaitAMessage();
    assertEquals(List.of(), gate.enrolments(), "the service was asked about a typo");
    assertTrue(lookup("#secret").tryQuery().isPresent(), "the person should still be here");
  }

  /** A refused code says what to do about it and names no Account. */
  @Test
  void aRefusedCodeSaysSoAndNamesNobody() {
    gate.answerTheEnrolmentWith(EnrolmentRefused.because(DeniedReason.AUTH_FAILED));
    goToTheEnrolmentScreen();

    enrol("K7QF-9M2X-3WBR-8ZDN-5YCG-VJH2-P0", CHOSEN, CHOSEN);

    String refusal = awaitAMessage();
    assertFalse(refusal.contains(NEWCOMER), () -> "named it: " + refusal);
    assertTrue(lookup("#secret").tryQuery().isPresent(), "the person should still be here");
  }

  /** A Lockout says how long, here as at the login screen: it is the same Lockout. */
  @Test
  void aLockedAccountIsToldHowLongItHasToWait() {
    gate.answerTheEnrolmentWith(EnrolmentRefused.lockedFor(Duration.ofMinutes(14).plusSeconds(30)));
    goToTheEnrolmentScreen();

    enrol(SECRET, CHOSEN, CHOSEN);

    String locked = awaitAMessage();
    assertTrue(locked.contains("15 minutos"), () -> "no wait to read in: " + locked);
  }

  /**
   * A password the policy refuses is a different thing entirely: the code is still good, and the
   * person is told every rule at once rather than discovering them one at a time.
   */
  @Test
  void aRefusedPasswordNamesEveryRuleAndLeavesTheCodeWhereItIs() {
    gate.answerTheEnrolmentWith(
        new PolicyRefusal(
            List.of(PolicyViolation.PASSWORD_TOO_SHORT, PolicyViolation.PASSWORD_WITHOUT_NUMBER)));
    goToTheEnrolmentScreen();

    enrol(SECRET, "short", "short");

    String refusal = awaitAMessage();
    assertNotEquals("", refusal);
    assertEquals(SECRET, textOf("#secret"), "the code should not have been cleared");
    assertEquals("", passwordFieldText("#password"), "the password should have been cleared");
    assertEquals("", passwordFieldText("#repeatedPassword"), "and so should the second one");
  }

  /** A service that cannot be reached is not a refused code, and must not read as one. */
  @Test
  void aServiceThatCannotBeReachedIsNotShownAsARefusal() {
    gate.answerTheEnrolmentWith(EnrolmentRefused.because(DeniedReason.AUTH_FAILED));
    goToTheEnrolmentScreen();
    enrol(SECRET, CHOSEN, CHOSEN);
    String refusal = awaitAMessage();

    gate.becomeUnreachable();
    enrol(SECRET, CHOSEN, CHOSEN);

    String unreachable = awaitAMessage(refusal);
    assertNotEquals(refusal, unreachable);
    assertFalse(unreachable.isBlank(), "an unreachable service should still say something");
  }

  @Test
  void nothingBehindTheGateIsBuiltByEnrolling() {
    goToTheEnrolmentScreen();

    enrol(SECRET, CHOSEN, CHOSEN);

    awaitTheLoginScreen();
    assertEquals(0, featuresBuilt.get(), "enrolling is not being admitted");
  }

  // --- driving the windows ---------------------------------------------------------------------

  private void goToTheEnrolmentScreen() {
    clickOn("#enrolInstead");
    awaitTheEnrolmentScreen();
  }

  /**
   * Types the name here rather than at the login screen, which is what somebody who arrived by the
   * button does: nothing came with them, and the field is theirs to fill.
   */
  private void enrol(String secret, String password, String repeated) {
    clickOn("#accountName").eraseText(textOf("#accountName").length()).write(NEWCOMER);
    clickOn("#secret").eraseText(textOf("#secret").length()).write(secret);
    clickOn("#password").write(password);
    clickOn("#repeatedPassword").write(repeated);
    clickOn("#enrol");
  }

  private void attemptToLogIn(String accountName, String password) {
    clickOn("#accountName").eraseText(textOf("#accountName").length()).write(accountName);
    clickOn("#password").write(password);
    clickOn("#admit");
  }

  private void awaitTheEnrolmentScreen() {
    await(() -> lookup("#enrol").tryQuery().isPresent());
  }

  private void awaitTheLoginScreen() {
    await(() -> lookup("#admit").tryQuery().isPresent());
  }

  private void awaitTheProtectedFeature() {
    await(() -> lookup("#feature").tryQuery().isPresent());
  }

  private String awaitAMessage() {
    return awaitAMessage("");
  }

  private String awaitAMessage(String previously) {
    await(() -> !message().isEmpty() && !message().equals(previously));
    return message();
  }

  private void await(BooleanSupplier condition) {
    try {
      WaitForAsyncUtils.waitFor(PATIENCE_IN_SECONDS, TimeUnit.SECONDS, condition::getAsBoolean);
    } catch (TimeoutException e) {
      throw new AssertionError("the window never got there; it said: " + message(), e);
    }
  }

  /** Whichever of the two screens is showing, this is what it is saying. */
  private String message() {
    return lookup("#enrolmentMessage")
        .tryQueryAs(Label.class)
        .or(() -> lookup("#message").tryQueryAs(Label.class))
        .map(Label::getText)
        .orElse("");
  }

  private String textOf(String query) {
    return lookup(query).queryAs(TextInputControl.class).getText();
  }

  private String passwordFieldText(String query) {
    return lookup(query).queryAs(PasswordField.class).getText();
  }
}
