package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.session.Session;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Seam 3: the login window, driven by TestFX on Monocle with no display, against a fake LoginGate.
 *
 * <p>Everything asserted here is the window's own behaviour — what it opens, what it closes, what
 * it says and what it refuses to say. Whether a password is right is the AuthenticationService's
 * business and is tested where that decision is made.
 */
class LoginWindowTest extends ApplicationTest {

  private static final String OPERATOR = "finch.mercer";
  private static final String PASSWORD = "Another-Horse-2";

  private static final int PATIENCE_IN_SECONDS = 10;

  private final AtomicInteger featuresBuilt = new AtomicInteger();
  private final List<Session> admittedOn = new CopyOnWriteArrayList<>();

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
    admittedOn.add(session);
    featuresBuilt.incrementAndGet();
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }

  @Test
  void anOperatorAuthenticatesAndTheProtectedFeatureOpens() {
    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertEquals(1, featuresBuilt.get(), "the feature should have been built exactly once");
  }

  /** Story 33: the gate must not linger behind the feature it let someone through. */
  @Test
  void theLoginStageClosesOnceAccessIsGranted() {
    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertFalse(loginStage.isShowing(), "the login window should have closed");
  }

  @Test
  void nothingBehindTheGateIsBuiltUntilSomeoneIsAdmitted() {
    assertEquals(0, featuresBuilt.get(), "the feature was built before anyone was admitted");

    attempt(OPERATOR, "Wrong-Horse-9");

    awaitAMessage();
    assertEquals(0, featuresBuilt.get(), "a refused attempt built the feature anyway");
    assertTrue(loginStage.isShowing(), "the login window should still be there");
  }

  /**
   * Story 34: the screen says only that authentication failed. Two attempts that failed for
   * different reasons have to read identically, or the window becomes the oracle for the account
   * list that ADR-0002 exists to deny.
   */
  @Test
  void aRefusalSaysNothingAboutWhetherTheAccountExists() {
    attempt(OPERATOR, "Wrong-Horse-9");
    String forAWrongPassword = awaitAMessage();

    attempt("nobody.here", PASSWORD);
    String forAnUnknownAccount = awaitAMessage();

    assertEquals(forAWrongPassword, forAnUnknownAccount);
    assertFalse(forAWrongPassword.contains(OPERATOR), () -> "named it: " + forAWrongPassword);
  }

  @Test
  void aRefusalClearsThePasswordAndLeavesTheNameAlone() {
    attempt(OPERATOR, "Wrong-Horse-9");

    awaitAMessage();
    assertEquals("", passwordField().getText(), "the password should have been cleared");
    assertEquals(OPERATOR, textOf("#accountName"), "the name should not have been cleared");
  }

  /**
   * A service that cannot be reached is not a wrong password, and sending the person to retype one
   * would be sending them nowhere.
   */
  @Test
  void aServiceThatCannotBeReachedIsNotShownAsARefusal() {
    attempt(OPERATOR, "Wrong-Horse-9");
    String refusal = awaitAMessage();

    gate.becomeUnreachable();
    attempt(OPERATOR, PASSWORD);
    String unreachable = awaitAMessage(refusal);

    assertNotEquals(refusal, unreachable);
    assertFalse(unreachable.isBlank(), "an unreachable service should still say something");
    assertEquals(0, featuresBuilt.get(), "nothing should have opened");
  }

  /**
   * CONTEXT.md: the LoginGate is what a host product calls to <em>obtain a Session</em>. This one
   * has no use for it yet, and it is still handed the Session that admitting the Operator produced
   * rather than being told only that something happened.
   */
  @Test
  void handsTheHostTheSessionThatAdmittingSomeoneProduced() {
    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertEquals(1, admittedOn.size(), "the host should have been handed one Session");
    assertEquals(16, admittedOn.get(0).token().copyOfBytes().length);
  }

  @Test
  void offersTheGateExactlyWhatWasTyped() {
    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertEquals(List.of(OPERATOR + "/" + PASSWORD), gate.attempts());
  }

  // --- driving the window ------------------------------------------------------------------

  private void attempt(String accountName, String password) {
    clickOn("#accountName").eraseText(textOf("#accountName").length()).write(accountName);
    clickOn("#password").write(password);
    clickOn("#admit");
  }

  private void awaitTheProtectedFeature() {
    await(() -> lookup("#feature").tryQuery().isPresent());
  }

  /** Waits for the window to say something, and answers with what it said. */
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

  private String message() {
    return lookup("#message").queryAs(Label.class).getText();
  }

  private String textOf(String query) {
    return lookup(query).queryAs(TextInputControl.class).getText();
  }

  private PasswordField passwordField() {
    return lookup("#password").queryAs(PasswordField.class);
  }
}
