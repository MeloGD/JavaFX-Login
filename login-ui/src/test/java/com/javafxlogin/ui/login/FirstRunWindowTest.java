package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.Session;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: the first-run wizard, driven by TestFX on Monocle with no display, against a fake
 * LoginGate.
 *
 * <p>Everything asserted here is the window's own behaviour — which window opens, what is in the
 * fields when it does, what it warns about, and what it says when the service refuses. Whether a
 * name or a password is allowed, and whether the person at the keyboard may create the
 * Administrator at all, are the AuthenticationService's decisions and are tested where they are
 * made.
 */
class FirstRunWindowTest extends ApplicationTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String PASSWORD = "Correct-Horse-1";

  private static final int PATIENCE_IN_SECONDS = 10;

  private FakeLoginGate gate;
  private Stage stage;

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    gate = new FakeLoginGate().needingItsAdministrator();
    gate.protect(stage, this::protectedFeature);
  }

  private Parent protectedFeature(Session session) {
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }

  @Test
  void theWizardOpensInsteadOfTheLoginScreenWhereThereIsNoAdministrator() {
    assertTrue(lookup("#create").tryQuery().isPresent(), "the wizard should be on the screen");
    assertTrue(lookup("#admit").tryQuery().isEmpty(), "the login screen should not be");
  }

  /**
   * ADR-0002: because the account list cannot be read, a predictable name hands an entry of it back
   * for free. So nothing is typed in for the person, and nothing is suggested to them either — a
   * prompt reading {@code admin} would be this application naming the first Account an attacker
   * guesses.
   */
  @Test
  void theAccountNameFieldIsEmptyAndSuggestsNothing() {
    TextInputControl name = lookup("#administratorName").queryAs(TextInputControl.class);

    assertEquals("", name.getText(), "the name field should be empty");
    assertTrue(
        name.getPromptText() == null || name.getPromptText().isEmpty(),
        () -> "the name field suggests a name: " + name.getPromptText());
  }

  @Test
  void thePasswordFieldIsEmptyAndSuggestsNothing() {
    TextInputControl password = lookup("#administratorPassword").queryAs(TextInputControl.class);

    assertEquals("", password.getText(), "the password field should be empty");
    assertTrue(
        password.getPromptText() == null || password.getPromptText().isEmpty(),
        () -> "the password field suggests a password: " + password.getPromptText());
  }

  /**
   * The person has to be told before they choose, not after. There is no recovery key, no backup
   * code and no reset, and nothing later in this product will issue one.
   */
  @Test
  void warnsThatThePasswordCannotBeRecoveredAndSaysWhereToKeepIt() {
    String warning =
        lookup("#recoveryWarning").queryAs(Label.class).getText().toLowerCase(Locale.ROOT);

    assertTrue(warning.contains("no se puede recuperar"), () -> "no warning: " + warning);
    assertTrue(warning.contains("gestor de contraseñas"), () -> "no remedy: " + warning);
  }

  @Test
  void offersTheGateExactlyWhatWasTyped() {
    create(ADMINISTRATOR, PASSWORD);

    awaitTheLoginScreen();
    assertEquals(List.of(ADMINISTRATOR + "/" + PASSWORD), gate.creations());
  }

  /** Story: once the Administrator exists, the way in is the login screen, on the same stage. */
  @Test
  void theLoginScreenReplacesTheWizardOnceTheAdministratorExists() {
    create(ADMINISTRATOR, PASSWORD);

    awaitTheLoginScreen();
    assertTrue(lookup("#create").tryQuery().isEmpty(), "the wizard should be gone");
    assertTrue(stage.isShowing(), "the login screen should be on the stage the wizard had");
  }

  @Test
  void nothingBehindTheGateIsOpenedByCreatingTheAdministrator() {
    create(ADMINISTRATOR, PASSWORD);

    awaitTheLoginScreen();
    assertTrue(lookup("#feature").tryQuery().isEmpty(), "the wizard opened the ProtectedFeature");
  }

  /**
   * A refusal has to be told apart from a bug, which means naming every rule that was broken rather
   * than the first one, in words rather than in constants.
   */
  @Test
  void aPolicyRefusalNamesEveryRuleThatWasBrokenInWordsAPersonReads() {
    gate.answerTheWizardWith(
        new PolicyRefusal(
            List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED, PolicyViolation.PASSWORD_TOO_SHORT)));

    create("admin", "short");

    String message = awaitAMessage();
    assertTrue(message.contains("12 caracteres"), () -> "the password rule is missing: " + message);
    assertTrue(message.contains("adivinar"), () -> "the name rule is missing: " + message);
    assertFalse(message.contains("_"), () -> "a constant reached the screen: " + message);
    assertTrue(lookup("#create").tryQuery().isPresent(), "the wizard should still be there");
  }

  /** The name was refused, not the password. Making someone retype a good password is gratuitous. */
  @Test
  void aRefusalLeavesWhatWasTypedWhereItIs() {
    gate.answerTheWizardWith(new PolicyRefusal(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED)));

    create("admin", PASSWORD);

    awaitAMessage();
    assertEquals("admin", textOf("#administratorName"));
    assertEquals(PASSWORD, textOf("#administratorPassword"));
  }

  /**
   * Nothing about the Account was reached, so the remedy is not to type something else. The window
   * says which of the two refusals it was, because the two have nothing in common.
   */
  @Test
  void saysWhoMayRunTheWizardWhenTheMachineRefusesThePeer() {
    gate.answerTheWizardWith(new WizardRefused(WizardRefusedReason.NOT_MACHINE_ADMINISTRATOR));

    create(ADMINISTRATOR, PASSWORD);

    String message = awaitAMessage();
    assertTrue(message.contains("administre el equipo"), () -> "unhelpful refusal: " + message);
    assertTrue(lookup("#create").tryQuery().isPresent(), "the wizard should still be there");
  }

  @Test
  void saysSomethingElseWhenTheAdministratorAlreadyExists() {
    gate.answerTheWizardWith(new WizardRefused(WizardRefusedReason.ADMINISTRATOR_EXISTS));
    create(ADMINISTRATOR, PASSWORD);
    String existing = awaitAMessage();

    gate.answerTheWizardWith(new WizardRefused(WizardRefusedReason.NOT_MACHINE_ADMINISTRATOR));
    create(ADMINISTRATOR, PASSWORD);
    String refused = awaitAMessage(existing);

    assertNotEquals(existing, refused);
    assertFalse(existing.isBlank(), "an Administrator that exists should still say something");
  }

  /** Not being able to ask is not a refusal, and must not read as one. */
  @Test
  void aServiceThatCannotBeReachedIsNotShownAsARefusal() {
    gate.answerTheWizardWith(new PolicyRefusal(List.of(PolicyViolation.PASSWORD_TOO_SHORT)));
    create(ADMINISTRATOR, "short");
    String refusal = awaitAMessage();

    gate.becomeUnreachable();
    create(ADMINISTRATOR, PASSWORD);
    String unreachable = awaitAMessage(refusal);

    assertNotEquals(refusal, unreachable);
    assertFalse(unreachable.isBlank(), "an unreachable service should still say something");
  }

  // --- driving the window ------------------------------------------------------------------

  private void create(String administratorName, String password) {
    clickOn("#administratorName")
        .eraseText(textOf("#administratorName").length())
        .write(administratorName);
    clickOn("#administratorPassword")
        .eraseText(textOf("#administratorPassword").length())
        .write(password);
    clickOn("#create");
  }

  private void awaitTheLoginScreen() {
    await(() -> lookup("#admit").tryQuery().isPresent());
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

  private String message() {
    return lookup("#firstRunMessage").queryAs(Label.class).getText();
  }

  private String textOf(String query) {
    return lookup(query).queryAs(TextInputControl.class).getText();
  }
}
