package com.javafxlogin.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * ADR-0007: there is no JPMS here, so the separation between the UI and the authentication logic is
 * enforced by the build alone — {@code login-core} has no JavaFX on its classpath and therefore
 * cannot import it even by accident.
 *
 * <p>A dependency added by hand would undo that silently, and the compiler would say nothing until
 * someone noticed the service's tests needed a display. This asks for the class the whole toolkit
 * hangs off and expects not to find it.
 */
class NoJavaFxOnTheCoreClasspathTest {

  @Test
  void theToolkitIsNotReachableFromHere() {
    assertThrows(ClassNotFoundException.class, () -> Class.forName("javafx.stage.Stage"));
  }

  @Test
  void norIsAnyOfItsControls() {
    assertThrows(ClassNotFoundException.class, () -> Class.forName("javafx.scene.control.Button"));
  }
}
