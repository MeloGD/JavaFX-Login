package com.javafxlogin.ui.login;

import java.util.Objects;
import javafx.stage.Stage;

/**
 * The first-run wizard, and what replaces it once the single Administrator exists.
 *
 * <p>It takes the same stage the login window would have had and hands it back: this is the first
 * of two screens rather than a second window, so nothing about the wizard is left behind once it is
 * done, and the person is not asked to find another window in order to log in.
 *
 * <p>It is only ever opened where no Administrator exists. That the wizard is <em>refused</em>
 * unless the peer administers the machine is not decided here, and cannot be: the service decides
 * it, and this window would be refused whatever it showed.
 */
final class FirstRunWindow {

  private static final String FXML = "first-run-window.fxml";

  private static final String TITLE = "first-run.title";

  private FirstRunWindow() {}

  /**
   * @param language the machine's own, because this screen is shown before the deployment holds a
   *     single Account and so before there is any LanguagePreference in the world to read
   */
  static void show(LoginGate gate, Stage stage, InterfaceLanguage language, Runnable onCreated) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(language, "language");
    Objects.requireNonNull(onCreated, "onCreated");

    GateWindow window = GateWindow.loadedFrom(FXML, language);
    window.controller(FirstRunController.class).createWith(gate, language, onCreated);
    window.showOn(stage, language.say(TITLE));
  }
}
