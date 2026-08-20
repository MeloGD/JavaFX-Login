package com.javafxlogin.ui.login;

import java.util.Objects;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * What the first-run wizard does when someone types into it.
 *
 * <p>It decides nothing about who may create the Administrator, and nothing about whether a name or
 * a password is allowed. Both are the AuthenticationService's, which is what makes a patched copy
 * of this class worth nothing: it can send whatever it likes and be refused all the same.
 *
 * <p>What it does own is the wording. The service names a broken rule; this turns it into a
 * sentence in the language the window was drawn in, and it says every one of them at once so that a
 * person fixes the whole thing rather than discovering it a rule at a time.
 *
 * <p>The attempt runs off the JavaFX application thread, because a password is hashed at the other
 * end and a window frozen for the length of an Argon2id hash looks broken.
 */
public final class FirstRunController {

  private static final String ADMINISTRATOR_EXISTS = "first-run.administrator-exists";

  private static final String NOT_MACHINE_ADMINISTRATOR = "first-run.not-machine-administrator";

  @FXML private TextField administratorName;
  @FXML private PasswordField administratorPassword;
  @FXML private Button create;
  @FXML private Label firstRunMessage;

  private LoginGate gate;
  private InterfaceLanguage saidIn;
  private Runnable onCreated;

  /** Wires the window to the gate behind it, and to whatever opens once the Account exists. */
  void createWith(LoginGate gate, InterfaceLanguage saidIn, Runnable onCreated) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.saidIn = Objects.requireNonNull(saidIn, "saidIn");
    this.onCreated = Objects.requireNonNull(onCreated, "onCreated");
  }

  @FXML
  private void onCreate() {
    String name = administratorName.getText();
    char[] secret = administratorPassword.getText().toCharArray();

    showWaiting(true);
    GateAttempt.make(
        "first-run-attempt",
        secret,
        () -> gate.createAdministrator(name, secret),
        this::showOutcome,
        this::refusedSaying);
  }

  private void showOutcome(FirstRunOutcome outcome) {
    switch (outcome) {
      case AdministratorCreated ignored -> created();
      case PolicyRefusal refusal ->
          refused(PolicyViolationText.paragraphFor(saidIn, refusal.violations()));
      case FirstRunRefused refused ->
          refusedSaying(
              switch (refused.reason()) {
                case ADMINISTRATOR_EXISTS -> ADMINISTRATOR_EXISTS;
                case NOT_MACHINE_ADMINISTRATOR -> NOT_MACHINE_ADMINISTRATOR;
              });
    }
  }

  private void created() {
    // The window is about to be replaced, but the password it holds is blanked first rather than
    // being left in a control the scene graph may keep alive.
    administratorPassword.clear();
    onCreated.run();
  }

  /** What this window says about a refusal, from the key of it. */
  private void refusedSaying(String key) {
    refused(saidIn.say(key));
  }

  /**
   * The password is left where it is. Unlike a refused login, nothing here says it was wrong — a
   * name the policy would not have is no reason to make someone retype a password it would.
   */
  private void refused(String reason) {
    firstRunMessage.setText(reason);
    showWaiting(false);
  }

  /** Refuses a second attempt while one is in flight, so that two answers cannot race. */
  private void showWaiting(boolean inFlight) {
    create.setDisable(inFlight);
    administratorName.setDisable(inFlight);
    administratorPassword.setDisable(inFlight);
    if (inFlight) {
      firstRunMessage.setText("");
    }
  }
}
