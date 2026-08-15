package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.Objects;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The login window, what replaces it once someone is admitted, and what puts it back when their
 * Session ends.
 *
 * <p>The stage is the same one throughout. Someone admitted, and then returned by an expiry or by
 * logging out, is looking at one application that changed what it was showing rather than at
 * windows appearing and disappearing around them — and the window they are handed back to is loaded
 * fresh, so nothing they typed an hour ago is still in it.
 */
final class LoginWindow {

  private static final String FXML = "login-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String LOGIN_TITLE = "Iniciar sesión";

  private LoginWindow() {}

  static void show(LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature) {
    show(gate, stage, protectedFeature, "");
  }

  /**
   * @param saying what to tell the person before they have done anything — why they are back at
   *     this window, where they arrived at it by a Session ending
   */
  private static void show(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, String saying) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(protectedFeature, "protectedFeature");

    GateWindow window = GateWindow.loadedFrom(FXML);
    window
        .controller(LoginController.class)
        .admitWith(gate, session -> hold(gate, stage, protectedFeature, session), saying);
    window.showOn(stage, LOGIN_TITLE);
  }

  /**
   * Opens the window the Operator works in, and closes the login window behind it.
   *
   * <p>That order is deliberate, in both directions: closing the only window a JavaFX application
   * has shown ends the toolkit, so whichever window is arriving is shown before the one it replaces
   * goes.
   */
  private static void hold(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, Session session) {
    SessionWindow.open(
        gate,
        session,
        protectedFeature.apply(session),
        sentence -> show(gate, stage, protectedFeature, sentence));
    stage.close();
  }
}
