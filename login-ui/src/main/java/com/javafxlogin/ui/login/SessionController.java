package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

  /**
   * Story 29, and every string here moves to a ResourceBundle when the interface learns a second
   * language. It says who did it and when, because those are the two things that let the person tell
   * a reset they were expecting from one they were not.
   */
  private static final String PASSWORD_WAS_RESET =
      "Tu contraseña fue restablecida por la administración el %s. Si no lo habías pedido,"
          + " comunícalo.";

  /**
   * The moment as this machine writes moments. The audit log's own format is ISO-8601 because it is
   * read by tools; this one is read by a person over their own shoulder.
   */
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

  @FXML private BorderPane root;
  @FXML private StackPane protectedFeature;
  @FXML private Button logOut;
  @FXML private Label notice;

  private LoginGate gate;
  private Session session;
  private Consumer<String> handBack;
  private SessionGuard guard;
  private boolean over;

  /**
   * Puts the host's view inside, sets a guard watching everything done to it, and says the one thing
   * the service said only once.
   */
  void hold(LoginGate gate, Admitted admitted, Parent view, Consumer<String> handBack) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.handBack = Objects.requireNonNull(handBack, "handBack");
    Objects.requireNonNull(admitted, "admitted");
    this.session = admitted.session();

    admitted.passwordResetAt().ifPresent(resetAt -> notice.setText(sentenceFor(resetAt)));
    protectedFeature.getChildren().add(Objects.requireNonNull(view, "view"));
    guard = SessionGuard.watching(root, gate, session, this::theSessionEnded);
  }

  private static String sentenceFor(Instant resetAt) {
    return PASSWORD_WAS_RESET.formatted(WHEN.format(resetAt));
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
