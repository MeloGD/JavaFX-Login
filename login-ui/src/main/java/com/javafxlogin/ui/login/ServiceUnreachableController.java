package com.javafxlogin.ui.login;

import java.util.Objects;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/**
 * The one screen with nothing to decide: it says why this application is not starting, and closes.
 *
 * <p>There is no retry. Under Linux socket activation a connection is what starts the service, so
 * the attempt that failed already was the retry — and the two remedies that are not "wait" involve
 * installing something or being added to a group, neither of which a button here could do.
 */
public final class ServiceUnreachableController {

  @FXML private Label remedy;

  private Runnable onQuit;

  /**
   * Puts the sentence on the window and wires its one control, before anybody can read either.
   *
   * @param saidIn the language this window was drawn in, which is the machine's
   * @param saying the key of the sentence naming what happened and what to do about it, worded here
   *     because this window is the one that knows which language it was drawn in
   * @param onQuit what the one control does, which is to end the application
   */
  void say(InterfaceLanguage saidIn, String saying, Runnable onQuit) {
    Objects.requireNonNull(saidIn, "saidIn");
    Objects.requireNonNull(saying, "saying");
    this.onQuit = Objects.requireNonNull(onQuit, "onQuit");
    remedy.setText(saidIn.say(saying));
  }

  @FXML
  private void onQuit() {
    onQuit.run();
  }
}
