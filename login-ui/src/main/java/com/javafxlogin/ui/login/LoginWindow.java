package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.Objects;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * The login window, and what replaces it once someone is admitted.
 *
 * <p>The ProtectedFeature opens on a stage of its own and the login stage is then closed, so the
 * gate does not linger behind the feature. It opens first and the gate closes second, deliberately:
 * closing the only window a JavaFX application has shown ends the toolkit, and the feature would go
 * with it.
 */
final class LoginWindow {

  private static final String FXML = "login-window.fxml";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String LOGIN_TITLE = "Iniciar sesión";

  private static final String FEATURE_TITLE = "Funcionalidad protegida";

  private LoginWindow() {}

  static void show(LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(protectedFeature, "protectedFeature");

    GateWindow window = GateWindow.loadedFrom(FXML);
    window
        .controller(LoginController.class)
        .admitWith(gate, session -> openProtectedFeature(stage, protectedFeature, session));
    window.showOn(stage, LOGIN_TITLE);
  }

  private static void openProtectedFeature(
      Stage loginStage, Function<Session, Parent> protectedFeature, Session session) {
    Stage featureStage = new Stage();
    featureStage.setTitle(FEATURE_TITLE);
    featureStage.setScene(new Scene(protectedFeature.apply(session)));
    featureStage.show();
    loginStage.close();
  }
}
