package com.javafxlogin.ui.login;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.fxml.FXMLLoader;
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
  private static final String STYLESHEET = "login.css";

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String LOGIN_TITLE = "Iniciar sesión";

  private static final String FEATURE_TITLE = "Funcionalidad protegida";

  private LoginWindow() {}

  static void show(LoginGate gate, Stage stage, Supplier<Parent> protectedFeature) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(protectedFeature, "protectedFeature");

    FXMLLoader loader = new FXMLLoader(resource(FXML));
    Parent root = load(loader);
    LoginController controller = loader.getController();
    controller.admitWith(gate, session -> openProtectedFeature(stage, protectedFeature));

    stage.setTitle(LOGIN_TITLE);
    stage.setScene(dressed(root));
    stage.show();
  }

  private static void openProtectedFeature(Stage loginStage, Supplier<Parent> protectedFeature) {
    Stage featureStage = new Stage();
    featureStage.setTitle(FEATURE_TITLE);
    featureStage.setScene(new Scene(protectedFeature.get()));
    featureStage.show();
    loginStage.close();
  }

  private static Scene dressed(Parent root) {
    Scene scene = new Scene(root);
    scene.getStylesheets().add(resource(STYLESHEET).toExternalForm());
    return scene;
  }

  private static Parent load(FXMLLoader loader) {
    try {
      return loader.load();
    } catch (IOException e) {
      // The FXML ships inside the same jar as this class. If it cannot be read, the build is
      // broken rather than the machine, and no login window can be shown at all.
      throw new UncheckedIOException("Could not load the login window", e);
    }
  }

  private static URL resource(String name) {
    URL resource = LoginWindow.class.getResource(name);
    if (resource == null) {
      throw new IllegalStateException(name + " is missing from the jar this class came in");
    }
    return resource;
  }
}
