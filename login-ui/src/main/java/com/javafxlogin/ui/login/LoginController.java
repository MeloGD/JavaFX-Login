package com.javafxlogin.ui.login;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
  @FXML private CheckBox administer;
  @FXML private Button admit;
  @FXML private Label message;

  private LoginGate gate;
  private Consumer<Admitted> onAdmitted;
  private Consumer<Admitted> onAdministrator;
  private Consumer<String> onEnrolmentRequired;

  /**
   * Wires the window to the gate behind it, and to the two screens it can hand somebody on to.
   *
   * @param onAdministrator given the Session an Administrator was admitted with, and expected to
   *     show the administration panel. It is a second callback rather than a branch inside the
   *     first because the two lead to different windows: an Administrator never reaches the
   *     ProtectedFeature, so the host product's view is not built for them at all
   * @param onEnrolmentRequired given the name that was typed, and expected to show the enrolment
   *     screen. Sending them there is the whole point of the service answering that refusal apart
   *     from the others: an Account with no password cannot be reached by typing a better one.
   * @param saying what the window says before anyone has typed anything, which is where someone
   *     returned here by a Session ending, or by finishing an enrolment, is told why
   */
  void admitWith(
      LoginGate gate,
      Consumer<Admitted> onAdmitted,
      Consumer<Admitted> onAdministrator,
      Consumer<String> onEnrolmentRequired,
      String saying) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.onAdmitted = Objects.requireNonNull(onAdmitted, "onAdmitted");
    this.onAdministrator = Objects.requireNonNull(onAdministrator, "onAdministrator");
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

  /**
   * Story 37: one screen, and the checkbox is the whole of the difference.
   *
   * <p>What the box decides is which Role is asked for, and the service decides whether the Account
   * holds it — an Operator who ticks it is refused, in the same words as a wrong password, because
   * telling the two apart would name the Role an Account holds.
   */
  @FXML
  private void onAdmit() {
    String name = accountName.getText();
    char[] secret = password.getText().toCharArray();
    // Which attempt this is, and where whoever makes it ends up, are chosen together and once:
    // they are the two halves of one decision, and a flag carried through both would let them
    // drift into asking for one Role and opening the other one's window.
    boolean administering = administer.isSelected();
    Supplier<Admission> attempt =
        administering ? () -> gate.administer(name, secret) : () -> gate.admit(name, secret);
    Consumer<Admitted> whereTheyGo = administering ? onAdministrator : onAdmitted;

    showWaiting(true);
    GateAttempt.make(
        "login-attempt",
        secret,
        attempt,
        admission -> showOutcome(admission, whereTheyGo),
        this::failed);
  }

  private void showOutcome(Admission admission, Consumer<Admitted> whereTheyGo) {
    switch (admission) {
      case Admitted admitted -> whereTheyGo.accept(admitted);
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
    administer.setDisable(inFlight);
    if (inFlight) {
      message.setText("");
    }
  }
}
