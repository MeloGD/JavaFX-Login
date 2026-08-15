package com.javafxlogin.ui.login;

import java.util.Objects;
import javafx.stage.Stage;

/**
 * The first-run wizard, and what replaces it once the single Administrator exists.
 *
 * <p>It takes the same stage the login window would have had and hands it back: this is the first
 * of two screens rather than a second window, so nothing about the wizard is left behind once it is
 * done, and the person is not asked to find another window in order to log in.
 *
 * <p>It is only ever opened where no Administrator exists. That the wizard is <em>refused</em>
 * unless the peer administers the machine is not decided here, and cannot be: the service decides
 * it, and this window would be refused whatever it showed.
 */
final class FirstRunWindow {

  private static final String FXML = "first-run-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String FIRST_RUN_TITLE = "Primer arranque";

  private FirstRunWindow() {}

  static void show(LoginGate gate, Stage stage, Runnable onCreated) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(onCreated, "onCreated");

    GateWindow window = GateWindow.loadedFrom(FXML);
    window.controller(FirstRunController.class).createWith(gate, onCreated);
    window.showOn(stage, FIRST_RUN_TITLE);
  }
}
