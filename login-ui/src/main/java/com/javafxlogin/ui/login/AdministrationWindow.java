package com.javafxlogin.ui.login;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.stage.Stage;

/**
 * The window an Administrator runs the deployment from: the Accounts, the configuration, and the
 * copy of the record.
 *
 * <p>It is a window of the gate's own on a stage of its own, exactly as the one an Operator works
 * in is, and for the same two reasons: an Administrator needs somewhere to leave deliberately, and
 * the window has to close and hand the person back to the login screen when the
 * AuthenticationService says the Session is over. What is different is what goes inside — no host
 * product's view is ever built for an Administrator, because an Administrator does not reach the
 * ProtectedFeature.
 *
 * <p>Nothing here decides who may see it. Every request the panel makes is refused in the
 * privileged process unless the Session it names is an Administrator's, which is what makes the
 * restriction real: a client patched into drawing this window draws an empty list and is refused
 * everything it clicks.
 */
final class AdministrationWindow {

  private static final String FXML = "administration-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String TITLE = "Administración";

  private AdministrationWindow() {}

  /**
   * Opens the panel, and closes it again when the Session ends however it ends.
   *
   * @param handBack given the sentence explaining why, and expected to show the login screen
   */
  static void open(LoginGate gate, Admitted admitted, Consumer<String> handBack) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(admitted, "admitted");
    Objects.requireNonNull(handBack, "handBack");

    Stage stage = new Stage();
    GateWindow window = GateWindow.loadedFrom(FXML);
    AdministrationController controller = window.controller(AdministrationController.class);
    // However this window goes — the Session ending, or the person closing it with the window
    // decoration — nothing is left watching a Session behind a window that is not there.
    stage.setOnHidden(event -> controller.stopWatching());
    controller.administer(
        gate,
        admitted.session(),
        sentence -> {
          handBack.accept(sentence);
          stage.close();
        });
    window.showOn(stage, TITLE);
  }
}
