package com.javafxlogin.ui.login;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.stage.Stage;

/**
 * The screen where somebody turns the secret they were handed into a password of their own.
 *
 * <p>It takes the stage the login window was on and gives it back, as the first-run wizard does:
 * this is the same application showing something else, and the person is not asked to go and find
 * another window. They arrive here by having been told at the login screen that their Account is
 * waiting for enrolment, or by saying so themselves.
 *
 * <p>Nothing about who may enrol is decided here, and nothing can be. The secret is the
 * AuthenticationService's to recognise, expire and consume, and this window would be refused
 * whatever it showed.
 */
final class EnrolmentWindow {

  private static final String FXML = "enrolment-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String ENROLMENT_TITLE = "Establecer contraseña";

  private EnrolmentWindow() {}

  /**
   * @param accountName what to start the name box with — the name typed at the login screen, where
   *     the person arrived here by being sent
   * @param onEnrolled given the sentence to greet them with, and expected to show the login screen
   */
  static void show(
      LoginGate gate, Stage stage, String accountName, Consumer<String> onEnrolled) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(onEnrolled, "onEnrolled");

    GateWindow window = GateWindow.loadedFrom(FXML);
    window.controller(EnrolmentController.class).enrolWith(gate, accountName, onEnrolled);
    window.showOn(stage, ENROLMENT_TITLE);
  }
}
