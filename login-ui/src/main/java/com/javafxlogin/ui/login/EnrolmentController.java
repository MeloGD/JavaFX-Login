package com.javafxlogin.ui.login;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * What the enrolment screen does when somebody types into it.
 *
 * <p>It decides nothing. Whether the secret is the one, whether it has expired, whether it has
 * already been used and whether the chosen password is allowed are all the AuthenticationService's,
 * which is what makes a patched copy of this class worth nothing.
 *
 * <p>It owns two things instead. The wording — the service names a broken rule and this turns it
 * into a sentence — and the one rule that is genuinely the screen's: the password is typed twice and
 * the two have to match. That is not policy and is not sent anywhere. It is about a person choosing
 * a password nobody has ever seen, on a system with no recovery key, where a typo is an Account they
 * would have to be given another secret for.
 *
 * <p>The attempt runs off the JavaFX application thread, because a password is hashed at the other
 * end and a window frozen for the length of an Argon2id hash looks broken.
 */
public final class EnrolmentController {

  private static final String REFUSED = "enrolment.refused";

  /** The screen's own rule, and the only one it has. */
  private static final String PASSWORDS_DO_NOT_MATCH = "enrolment.passwords-do-not-match";

  /** Said at the login screen this hands back to, and so handed over as a key. */
  private static final String ENROLLED = "enrolment.enrolled";

  @FXML private TextField accountName;
  @FXML private TextField secret;
  @FXML private PasswordField password;
  @FXML private PasswordField repeatedPassword;
  @FXML private Button enrol;
  @FXML private Label enrolmentMessage;

  private LoginGate gate;
  private InterfaceLanguage saidIn;
  private Consumer<String> onEnrolled;

  /** Wires the window to the gate behind it, and to whatever opens once the Account has one. */
  void enrolWith(
      LoginGate gate,
      InterfaceLanguage saidIn,
      String startingName,
      Consumer<String> onEnrolled) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.saidIn = Objects.requireNonNull(saidIn, "saidIn");
    this.onEnrolled = Objects.requireNonNull(onEnrolled, "onEnrolled");
    accountName.setText(Objects.requireNonNull(startingName, "startingName"));
  }

  @FXML
  private void onEnrol() {
    if (!password.getText().equals(repeatedPassword.getText())) {
      refusedSaying(PASSWORDS_DO_NOT_MATCH);
      return;
    }

    String name = accountName.getText();
    char[] offered = secret.getText().toCharArray();
    char[] chosen = password.getText().toCharArray();

    showWaiting(true);
    GateAttempt.make(
        "enrolment-attempt",
        chosen,
        () -> {
          try {
            return gate.completeEnrolment(name, offered, chosen);
          } finally {
            Arrays.fill(offered, '\0');
          }
        },
        this::showOutcome,
        this::refusedSaying);
  }

  private void showOutcome(EnrolmentOutcome outcome) {
    switch (outcome) {
      case Enrolled ignored -> enrolled();
      case PolicyRefusal refusal ->
          refused(PolicyViolationText.paragraphFor(saidIn, refusal.violations()));
      case EnrolmentRefused refused ->
          refused(
              switch (refused.reason()) {
                // The same sentence the login screen says, because it is the same Lockout: a wrong
                // secret counts against an Account exactly as a wrong password does.
                case LOCKED_OUT -> LockoutText.forA(saidIn, refused.lockedFor().orElseThrow());
                // Everything else the service could say here is the one refusal it does say: the
                // secret was not the outstanding one. AUTH_FAILED is what it sends; the other two
                // are answers to questions this screen does not ask, and a person reading this
                // sentence is being sent to the right place whichever of them arrived.
                case AUTH_FAILED, SESSION_ALREADY_LIVE, ENROLMENT_REQUIRED -> saidIn.say(REFUSED);
              });
    }
  }

  private void enrolled() {
    // The window is about to be replaced, but both copies of the password are blanked first rather
    // than being left in controls the scene graph may keep alive.
    password.clear();
    repeatedPassword.clear();
    onEnrolled.accept(ENROLLED);
  }

  /** What this window says about a refusal, from the key of it: everything but the Lockout. */
  private void refusedSaying(String key) {
    refused(saidIn.say(key));
  }

  /**
   * Both passwords are cleared and the secret is left where it is. A refusal here is almost never
   * about what was typed in the password boxes, but they are the two fields that must not be left
   * sitting on a screen somebody walks away from — and where the refusal <em>was</em> about them,
   * retyping both is what the person has to do anyway.
   */
  private void refused(String reason) {
    enrolmentMessage.setText(reason);
    password.clear();
    repeatedPassword.clear();
    showWaiting(false);
    password.requestFocus();
  }

  /** Refuses a second attempt while one is in flight, so that two answers cannot race. */
  private void showWaiting(boolean inFlight) {
    enrol.setDisable(inFlight);
    accountName.setDisable(inFlight);
    secret.setDisable(inFlight);
    password.setDisable(inFlight);
    repeatedPassword.setDisable(inFlight);
    if (inFlight) {
      enrolmentMessage.setText("");
    }
  }
}
