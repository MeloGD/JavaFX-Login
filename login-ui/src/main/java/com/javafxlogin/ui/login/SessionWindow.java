package com.javafxlogin.ui.login;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The window an admitted Operator works in: the host product's view, and one control of the gate's
 * own above it.
 *
 * <p>The gate owns this stage rather than handing the host a bare one, because two things have to
 * happen here that no host product should have to write and none should be able to forget. An
 * Operator needs somewhere to log out. And the window has to close, handing the person back to the
 * login screen, when the AuthenticationService says the Session is over — which it will say
 * whatever the window does, so a window that ignored it would leave someone looking at a view they
 * are no longer authenticated for.
 *
 * <p>What the host handed over is untouched: it is placed inside, and nothing here knows what it
 * is.
 *
 * <p>It is also where the one thing the service says only once gets said. An Operator whose password
 * an Administrator reset is told so on the admission that follows, and this is the first window they
 * see afterwards — the login screen they were told it on has already closed.
 */
final class SessionWindow {

  private static final String FXML = "session-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String FEATURE_TITLE = "Funcionalidad protegida";

  private SessionWindow() {}

  /**
   * Opens the window, and closes it again when the Session ends however it ends.
   *
   * <p>The login screen is put back before this window goes, and deliberately in that order:
   * closing the only window a JavaFX application has shown ends the toolkit, and the login screen
   * would go with it.
   *
   * @param handBack given the sentence explaining why, and expected to show the login screen
   */
  static void open(
      LoginGate gate, Admitted admitted, Parent protectedFeature, Consumer<String> handBack) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(admitted, "admitted");
    Objects.requireNonNull(protectedFeature, "protectedFeature");
    Objects.requireNonNull(handBack, "handBack");

    Stage stage = new Stage();
    GateWindow window = GateWindow.loadedFrom(FXML);
    SessionController controller = window.controller(SessionController.class);
    // However this window goes — the Session ending, or the person closing it with the window
    // decoration — nothing is left watching a Session behind a window that is not there. A Session
    // nobody reports activity for then expires, which is what expiry is for.
    stage.setOnHidden(event -> controller.stopWatching());
    controller.hold(
        gate,
        admitted,
        protectedFeature,
        sentence -> {
          handBack.accept(sentence);
          stage.close();
        });
    window.showOn(stage, FEATURE_TITLE);
  }
}
