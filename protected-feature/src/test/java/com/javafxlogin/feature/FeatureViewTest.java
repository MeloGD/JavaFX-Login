package com.javafxlogin.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The reference ProtectedFeature, on Monocle with no display.
 *
 * <p>It is deliberately thin: this module exists to show a host product what it has to write, and
 * what it has to write is a view. Whether the gate in front of it opens is tested where the gate
 * is.
 */
class FeatureViewTest extends ApplicationTest {

  @Override
  public void start(Stage stage) {
    stage.setScene(new Scene(FeatureView.load()));
    stage.show();
  }

  @Test
  void saysThatTheGateWasPassed() {
    assertEquals(
        "Has accedido a la funcionalidad detrás del sistema de login",
        lookup("#message").queryAs(Label.class).getText());
  }
}
