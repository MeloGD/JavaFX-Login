package com.javafxlogin.ui.login;

import java.util.Objects;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * What takes the login screen's place when the AuthenticationService could not be asked.
 *
 * <p>Story 90: the application refuses to start rather than degrading. It takes the stage the login
 * window would have had and never gives it back — nothing here leads anywhere, because there is
 * nowhere for it to lead until somebody fixes the machine.
 *
 * <p>Drawn in the machine's own language, like every screen shown before anybody has authenticated.
 * There is no selector on it: a person who cannot read this sentence is looking at a window with
 * one control, and the sentence it is not saying to them is one they could do nothing with either.
 */
final class ServiceUnreachableWindow {

  private static final String FXML = "service-unreachable-window.fxml";

  private ServiceUnreachableWindow() {}

  /**
   * Puts the refusal on {@code stage}, with the one control ending the application.
   *
   * @param saying the key of what happened and what to do about it — one of {@link
   *     ServiceUnreachableText}'s three, or the wording of a client that failed on its own
   */
  static void show(Stage stage, InterfaceLanguage language, String saying) {
    show(stage, language, saying, Platform::exit);
  }

  /**
   * As above, with what the one control does named rather than assumed.
   *
   * @param onQuit what the one control does, which is to end the application. It is an argument so
   *     that a test can drive this window without the toolkit it is running in being shut down
   *     underneath the rest of the suite.
   */
  static void show(Stage stage, InterfaceLanguage language, String saying, Runnable onQuit) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(language, "language");
    Objects.requireNonNull(saying, "saying");
    Objects.requireNonNull(onQuit, "onQuit");

    GateWindow window = GateWindow.loadedFrom(FXML, language);
    window.controller(ServiceUnreachableController.class).say(language, saying, onQuit);
    window.showOn(stage, language.say(ServiceUnreachableText.CANNOT_START));
  }
}
