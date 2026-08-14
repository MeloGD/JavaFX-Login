package com.javafxlogin.feature;

import javafx.application.Application;

/**
 * The class the JVM is pointed at.
 *
 * <p>It exists because of ADR-0007. This project runs on the classpath with no {@code
 * module-info.java}, and JavaFX refuses to start when the main class is itself an {@link
 * Application} subclass and {@code javafx.graphics} is not a named module — the failure is the
 * unhelpful "JavaFX runtime components are missing". Launching from a class that is not an
 * Application sidesteps it, and every host product that copies this module needs the same trick.
 */
public final class ProtectedFeatureLauncher {

  private ProtectedFeatureLauncher() {}

  public static void main(String[] args) {
    Application.launch(ProtectedFeatureApplication.class, args);
  }
}
