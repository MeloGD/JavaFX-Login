package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.time.Instant;
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
   * Story 29. It says who did it and when, because those are the two things that let a person tell
   * a reset they were expecting from one they were not — and it is said in the language that person
   * reads, with the moment written the way their language writes moments.
   */
  private static final String PASSWORD_WAS_RESET = "session.password-was-reset";

  @FXML private BorderPane root;
  @FXML private StackPane protectedFeature;
  @FXML private Button logOut;
  @FXML private Label notice;
  @FXML private Button noticeRead;

  private LoginGate gate;
  private InterfaceLanguage saidIn;
  private Session session;
  private Consumer<String> handBack;
  private SessionGuard guard;
  private boolean over;

  /**
   * Puts the host's view inside, sets a guard watching everything done to it, and says the one thing
   * the service said only once.
   */
  void hold(
      LoginGate gate,
      Admitted admitted,
      InterfaceLanguage saidIn,
      Parent view,
      Consumer<String> handBack) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.saidIn = Objects.requireNonNull(saidIn, "saidIn");
    this.handBack = Objects.requireNonNull(handBack, "handBack");
    Objects.requireNonNull(admitted, "admitted");
    this.session = admitted.session();

    admitted.passwordResetAt().ifPresent(resetAt -> show(sentenceFor(resetAt)));
    protectedFeature.getChildren().add(Objects.requireNonNull(view, "view"));
    guard = SessionGuard.watching(root, gate, session, this::theSessionEnded);
  }

  private void show(String sentence) {
    notice.setText(sentence);
    // Both controls are hidden until there is something to say, and they take no room while they
    // are: an empty notice would otherwise push the host product's view down the window for the
    // benefit of a sentence nobody is being shown.
    noticeRead.setVisible(true);
    noticeRead.setManaged(true);
  }

  private String sentenceFor(Instant resetAt) {
    return saidIn.say(PASSWORD_WAS_RESET, saidIn.moments().format(resetAt));
  }

  /**
   * The person says they have read it, which is the only thing that ends it.
   *
   * <p>The notice goes from the window first and the service is told afterwards, because the two are
   * not the same promise: the window is this person's and answers immediately, while telling the
   * service crosses the socket. A report that does not arrive costs nothing worse than the notice
   * being shown again at the next admission — which is the behaviour this whole arrangement exists
   * to have, so it fails in the safe direction.
   */
  @FXML
  private void onNoticeRead() {
    dismissTheNotice();
    GateAttempt.make(
        "password-reset-notice-read",
        () -> {
          gate.passwordResetNoticeWasRead(session);
          return null;
        },
        ignored -> {},
        // A service that could not be told is not something to put in front of the person: they
        // have read the notice, and the worst it costs is being shown it again next time.
        ignored -> {});
  }

  private void dismissTheNotice() {
    notice.setText("");
    noticeRead.setVisible(false);
    noticeRead.setManaged(false);
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
        // Not an answer, and not this window's to word either: it is said at the login screen, in
        // whatever language that one is drawn in.
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
   *
   * <p>What is handed back is the key of what to say and not the sentence: this window is closing,
   * and the login screen that says it is drawn in the language nobody had to authenticate for.
   */
  private void theSessionEnded(String saying) {
    // A Session can only end once, and two things watch for it: a logout in flight and the guard
    // asking. Whichever gets here first is the one the person is told about.
    if (over) {
      return;
    }
    over = true;
    stopWatching();
    handBack.accept(saying);
  }
}
