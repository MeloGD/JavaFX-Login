package com.javafxlogin.feature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/** The view this reference product puts behind the gate. It is ordinary FXML and nothing else. */
final class FeatureView {

  private static final String FXML = "feature-view.fxml";

  private FeatureView() {}

  /**
   * Built only when someone has been admitted: the gate is handed this method rather than the view
   * it returns, so nothing behind the gate exists until the gate opens.
   */
  static Parent load() {
    URL fxml = FeatureView.class.getResource(FXML);
    if (fxml == null) {
      throw new IllegalStateException(FXML + " is missing from the jar this class came in");
    }
    try {
      return FXMLLoader.load(fxml);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not load the ProtectedFeature's view", e);
    }
  }
}
