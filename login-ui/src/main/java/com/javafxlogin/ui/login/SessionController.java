package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/**
 * What the window an Operator works in does: hold the host product's view, watch for the Session
 * ending, and offer the one thing the gate puts on that window — a way to leave deliberately.
 *
 * <p>It decides nothing about when a Session is over. It is told, by the {@link SessionGuard},
 * which is told by the AuthenticationService.
 */
public final class SessionController {

  @FXML private BorderPane root;
  @FXML private StackPane protectedFeature;
  @FXML private Button logOut;

  private LoginGate gate;
  private Session session;
  private Consumer<String> handBack;
  private SessionGuard guard;
  private boolean over;

  /** Puts the host's view inside, and sets a guard watching everything done to it. */
  void hold(LoginGate gate, Session session, Parent view, Consumer<String> handBack) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.session = Objects.requireNonNull(session, "session");
    this.handBack = Objects.requireNonNull(handBack, "handBack");

    protectedFeature.getChildren().add(Objects.requireNonNull(view, "view"));
    guard = SessionGuard.watching(root, gate, session, this::theSessionEnded);
  }

  /**
   * Story 49. The window is left as it is until the service has answered: a person who clicked this
   * by mistake has nothing to go back to, so it is better that the control simply stops responding
   * for the moment the round trip takes.
   */
  @FXML
  private void onLogOut() {
    logOut.setDisable(true);
    GateAttempt.make(
        "logout",
        () -> {
          gate.logOut(session);
          return SessionEndedText.LOGGED_OUT;
        },
        this::theSessionEnded,
        this::theSessionEnded);
  }

  /** Stops the guard, whether or not this window was the one that noticed the Session end. */
  void stopWatching() {
    if (guard != null) {
      guard.stop();
    }
  }

  /**
   * However the Session ended — a logout, the clocks, a service that went away — this window is
   * done. The guard is stopped first: whatever it is in the middle of asking about is over.
   */
  private void theSessionEnded(String sentence) {
    // A Session can only end once, and two things watch for it: a logout in flight and the guard
    // asking. Whichever gets here first is the one the person is told about.
    if (over) {
      return;
    }
    over = true;
    stopWatching();
    handBack.accept(sentence);
  }
}
