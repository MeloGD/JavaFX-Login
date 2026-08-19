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
 *
 * <p>The enrolment screen shares that stage too, and hands it back the same way. An Account with no
 * password is not a wrong password, so being sent there is not being refused: it is this
 * application showing the one screen where the code somebody was given is worth something.
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
   *     this window, where they arrived at it by a Session ending or by finishing an enrolment
   */
  private static void show(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, String saying) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(protectedFeature, "protectedFeature");

    GateWindow window = GateWindow.loadedFrom(FXML);
    window
        .controller(LoginController.class)
        .admitWith(
            gate,
            admitted -> hold(gate, stage, protectedFeature, admitted),
            admitted -> administer(gate, stage, protectedFeature, admitted),
            accountName -> enrol(gate, stage, protectedFeature, accountName),
            saying);
    window.showOn(stage, LOGIN_TITLE);
  }

  /**
   * Puts the enrolment screen on the same stage, and puts the login window back when it is done.
   *
   * <p>Whether the enrolment succeeded or the person gave up and closed nothing, they end where they
   * started: at the login screen, with a sentence saying which of the two happened.
   */
  private static void enrol(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, String accountName) {
    EnrolmentWindow.show(
        gate, stage, accountName, sentence -> show(gate, stage, protectedFeature, sentence));
  }

  /**
   * Opens the administration panel, and closes the login window behind it.
   *
   * <p>The same arrangement as an admitted Operator's window, in the same order and for the same
   * reason — but with nothing of the host product in it. An Administrator does not reach the
   * ProtectedFeature, so its view is not built here at all; what is passed on is only what puts the
   * login screen back when the Session ends.
   */
  private static void administer(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, Admitted admitted) {
    AdministrationWindow.open(
        gate, admitted, sentence -> show(gate, stage, protectedFeature, sentence));
    stage.close();
  }

  /**
   * Opens the window the Operator works in, and closes the login window behind it.
   *
   * <p>That order is deliberate, in both directions: closing the only window a JavaFX application
   * has shown ends the toolkit, so whichever window is arriving is shown before the one it replaces
   * goes.
   */
  private static void hold(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, Admitted admitted) {
    SessionWindow.open(
        gate,
        admitted,
        protectedFeature.apply(admitted.session()),
        sentence -> show(gate, stage, protectedFeature, sentence));
    stage.close();
  }
}
