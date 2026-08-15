package com.javafxlogin.ui.login;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * One of the gate's own windows, loaded out of the jar this class came in.
 *
 * <p>Loading and showing are two steps rather than one so that a window is wired to whatever
 * answers for it before a person can click anything in it. There is only ever one of these on a
 * stage at a time: the wizard is replaced by the login screen on the stage they share, and the
 * window an admitted Operator works in takes a stage of its own — which is what lets the login
 * screen be put back on the first one when their Session ends.
 */
final class GateWindow {

  private static final String STYLESHEET = "login.css";

  private final Parent root;
  private final Object controller;

  private GateWindow(Parent root, Object controller) {
    this.root = root;
    this.controller = controller;
  }

  static GateWindow loadedFrom(String fxml) {
    FXMLLoader loader = new FXMLLoader(resource(fxml));
    try {
      Parent root = loader.load();
      return new GateWindow(root, loader.getController());
    } catch (IOException e) {
      // The FXML ships inside the same jar as this class. If it cannot be read, the build is
      // broken rather than the machine, and no window can be shown at all.
      throw new UncheckedIOException("Could not load " + fxml, e);
    }
  }

  /** The controller the FXML named, for the caller to wire before anyone sees the window. */
  <C> C controller(Class<C> type) {
    return type.cast(controller);
  }

  void showOn(Stage stage, String title) {
    Scene scene = new Scene(root);
    scene.getStylesheets().add(resource(STYLESHEET).toExternalForm());
    stage.setTitle(title);
    stage.setScene(scene);
    stage.show();
  }

  private static URL resource(String name) {
    URL resource = GateWindow.class.getResource(name);
    if (resource == null) {
      throw new IllegalStateException(name + " is missing from the jar this class came in");
    }
    return resource;
  }
}
