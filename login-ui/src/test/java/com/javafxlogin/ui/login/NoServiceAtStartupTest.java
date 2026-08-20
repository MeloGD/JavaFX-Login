package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.session.Session;
import java.util.Locale;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Seam 3: what opens when the AuthenticationService cannot be asked which window to open.
 *
 * <p>It is its own class because the question is asked once, before anything is drawn, and a test
 * that changed the answer afterwards would be asking about a window that already exists.
 *
 * <p>The choice is to show the login screen. Guessing the other way would put the wizard in front
 * of someone on a machine that may well have an Administrator already — and the login screen is
 * honest about what happened the moment they try, which is the behaviour {@link LoginWindowTest}
 * pins.
 */
class NoServiceAtStartupTest extends ApplicationTest {

  /**
   * The language every window in this test is drawn in, named rather than taken from the machine
   * the suite happens to be running on: what a screen says is asserted against the bundle it came
   * from, and a developer's locale is not a thing to assert against.
   */
  private static final InterfaceLanguage SPANISH =
      InterfaceLanguage.of(Locale.forLanguageTag("es"));

  private Stage stage;

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    FakeLoginGate gate = new FakeLoginGate().needingItsAdministrator();
    gate.becomeUnreachable();
    GateFlow.open(gate, stage, this::protectedFeature, SPANISH);
  }

  private Parent protectedFeature(Session session) {
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }

  /** Asked of this test's own stage, so that no window another test left behind can answer it. */
  @Test
  void showsTheLoginScreenRatherThanGuessingThatTheWizardIsNeeded() {
    Parent shown = stage.getScene().getRoot();

    assertTrue(
        from(shown).lookup("#admit").tryQuery().isPresent(),
        "the login screen should be on the stage");
    assertTrue(
        from(shown).lookup("#create").tryQuery().isEmpty(),
        "the wizard should not have been guessed");
  }
}
