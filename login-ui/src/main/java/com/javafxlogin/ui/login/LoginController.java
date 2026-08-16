package com.javafxlogin.ui.login;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * What the login window does when someone types into it.
 *
 * <p>It decides nothing about access. It collects two values, hands them to the {@link LoginGate},
 * and shows whichever of two outcomes came back — and the outcome it shows for a refusal is a
 * single sentence that says only that the attempt failed, because the service tells it no more
 * than that and a screen that appeared to know more would be an oracle for the account list.
 *
 * <p>The attempt runs off the JavaFX application thread. Verifying a password is meant to be slow,
 * and a window frozen for the length of an Argon2id hash looks broken.
 */
public final class LoginController {

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String REFUSED =
      "No se ha podido iniciar sesión. Revisa el nombre de cuenta y la contraseña.";

  /**
   * Said in its own words because retyping anything would not help. It reveals nothing about any
   * Account: a Session being open is already visible to whoever can see the screen it is open on.
   */
  private static final String SESSION_ALREADY_LIVE =
      "Ya hay una sesión abierta en este equipo. Ciérrala antes de iniciar otra.";

  @FXML private TextField accountName;
  @FXML private PasswordField password;
  @FXML private Button admit;
  @FXML private Label message;

  private LoginGate gate;
  private Consumer<Admitted> onAdmitted;
  private Consumer<String> onEnrolmentRequired;

  /**
   * Wires the window to the gate behind it, and to the two screens it can hand somebody on to.
   *
   * @param onEnrolmentRequired given the name that was typed, and expected to show the enrolment
   *     screen. Sending them there is the whole point of the service answering that refusal apart
   *     from the others: an Account with no password cannot be reached by typing a better one.
   * @param saying what the window says before anyone has typed anything, which is where someone
   *     returned here by a Session ending, or by finishing an enrolment, is told why
   */
  void admitWith(
      LoginGate gate,
      Consumer<Admitted> onAdmitted,
      Consumer<String> onEnrolmentRequired,
      String saying) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.onAdmitted = Objects.requireNonNull(onAdmitted, "onAdmitted");
    this.onEnrolmentRequired = Objects.requireNonNull(onEnrolmentRequired, "onEnrolmentRequired");
    message.setText(Objects.requireNonNull(saying, "saying"));
  }

  /**
   * The other way to the enrolment screen: somebody who was handed a code and has never had a
   * password to try. Without this they would have to type a password they do not have, be refused,
   * and be sent there — which works, and reads as the application not knowing what it wants.
   */
  @FXML
  private void onEnrolInstead() {
    onEnrolmentRequired.accept(accountName.getText());
  }

  @FXML
  private void onAdmit() {
    String name = accountName.getText();
    char[] secret = password.getText().toCharArray();

    showWaiting(true);
    GateAttempt.make(
        "login-attempt", secret, () -> gate.admit(name, secret), this::showOutcome, this::failed);
  }

  private void showOutcome(Admission admission) {
    switch (admission) {
      case Admitted admitted -> onAdmitted.accept(admitted);
      case NotAdmitted notAdmitted -> refused(notAdmitted);
    }
  }

  /**
   * Three of the four refusals are a sentence and the fourth is a window.
   *
   * <p>An Account awaiting enrolment is the fourth, and it is the reason the service answers that
   * refusal apart from the others: the person cannot fix it by typing a better password, because
   * there is no password to be better than. They are handed to the screen where the code they were
   * given is worth something, and the name goes with them so that they do not type it twice.
   */
  private void refused(NotAdmitted notAdmitted) {
    switch (notAdmitted.reason()) {
      case AUTH_FAILED -> failed(REFUSED);
      case SESSION_ALREADY_LIVE -> failed(SESSION_ALREADY_LIVE);
      // Present because the refusal is a Lockout, which is the record's own rule.
      case LOCKED_OUT -> failed(LockoutText.forA(notAdmitted.lockedFor().orElseThrow()));
      case ENROLMENT_REQUIRED -> onEnrolmentRequired.accept(accountName.getText());
    }
  }

  private void failed(String reason) {
    message.setText(reason);
    password.clear();
    showWaiting(false);
    password.requestFocus();
  }

  /** Refuses a second attempt while one is in flight, so that two answers cannot race. */
  private void showWaiting(boolean inFlight) {
    admit.setDisable(inFlight);
    accountName.setDisable(inFlight);
    password.setDisable(inFlight);
    if (inFlight) {
      message.setText("");
    }
  }
}
