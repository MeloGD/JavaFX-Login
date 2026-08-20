package com.javafxlogin.ui.login;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

/**
 * What the login window does when someone types into it.
 *
 * <p>It decides nothing about access. It collects two values, hands them to the {@link LoginGate},
 * and shows whichever of two outcomes came back — and the outcome it shows for a refusal is a
 * single sentence that says only that the attempt failed, because the service tells it no more
 * than that and a screen that appeared to know more would be an oracle for the account list.
 *
 * <p>It is also where somebody says which language they read. This screen has to be readable before
 * anybody has authenticated, so it is drawn in the machine's own language and offers the selector
 * for when that is not the language of whoever is sitting at it. Choosing one is not a request and
 * reaches no service: it redraws this window, and the Account's own LanguagePreference — which
 * nothing here knows yet — takes over the moment somebody is admitted.
 *
 * <p>The attempt runs off the JavaFX application thread. Verifying a password is meant to be slow,
 * and a window frozen for the length of an Argon2id hash looks broken.
 */
public final class LoginController {

  private static final String REFUSED = "login.refused";

  /**
   * Said in its own words because retyping anything would not help. It reveals nothing about any
   * Account: a Session being open is already visible to whoever can see the screen it is open on.
   */
  private static final String SESSION_ALREADY_LIVE = "login.session-already-live";

  @FXML private TextField accountName;
  @FXML private PasswordField password;
  @FXML private CheckBox administer;
  @FXML private ComboBox<Locale> language;
  @FXML private Button admit;
  @FXML private Label message;

  private LoginGate gate;
  private InterfaceLanguage saidIn;
  private Consumer<Admitted> onAdmitted;
  private Consumer<Admitted> onAdministrator;
  private Consumer<String> onEnrolmentRequired;

  /**
   * Wires the window to the gate behind it, and to the screens it can hand somebody on to.
   *
   * @param saidIn the language this window has been drawn in, which is what everything it says
   *     afterwards is said in
   * @param onAdministrator given the Session an Administrator was admitted with, and expected to
   *     show the administration panel. It is a second callback rather than a branch inside the
   *     first because the two lead to different windows: an Administrator never reaches the
   *     ProtectedFeature, so the host product's view is not built for them at all
   * @param onEnrolmentRequired given the name that was typed, and expected to show the enrolment
   *     screen. Sending them there is the whole point of the service answering that refusal apart
   *     from the others: an Account with no password cannot be reached by typing a better one.
   * @param onLanguageChosen given the language somebody picked from the selector, and expected to
   *     draw this window again in it
   * @param saying the key of what the window says before anyone has typed anything, or empty —
   *     which is where someone returned here by a Session ending, or by finishing an enrolment, is
   *     told why
   */
  void admitWith(
      LoginGate gate,
      InterfaceLanguage saidIn,
      Consumer<Admitted> onAdmitted,
      Consumer<Admitted> onAdministrator,
      Consumer<String> onEnrolmentRequired,
      Consumer<Locale> onLanguageChosen,
      String saying) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.saidIn = Objects.requireNonNull(saidIn, "saidIn");
    this.onAdmitted = Objects.requireNonNull(onAdmitted, "onAdmitted");
    this.onAdministrator = Objects.requireNonNull(onAdministrator, "onAdministrator");
    this.onEnrolmentRequired = Objects.requireNonNull(onEnrolmentRequired, "onEnrolmentRequired");
    Objects.requireNonNull(onLanguageChosen, "onLanguageChosen");
    offerTheLanguages(onLanguageChosen);
    message.setText(Objects.requireNonNull(saying, "saying").isEmpty() ? "" : saidIn.say(saying));
  }

  /**
   * Puts every language this build offers in the selector, each named in itself.
   *
   * <p>The one being read is chosen before anything is listening, so that drawing the window is not
   * itself somebody choosing a language and redrawing it forever.
   */
  private void offerTheLanguages(Consumer<Locale> onLanguageChosen) {
    language.getItems().setAll(InterfaceLanguage.offered());
    language.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(Locale offered) {
            return offered == null ? "" : InterfaceLanguage.nameOf(offered);
          }

          @Override
          public Locale fromString(String named) {
            throw new UnsupportedOperationException("the selector is not typed into");
          }
        });
    language.setValue(saidIn.locale());
    language
        .valueProperty()
        .addListener(
            (chosen, was, is) -> {
              if (is != null && !is.equals(was)) {
                onLanguageChosen.accept(is);
              }
            });
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
        this::failedSaying);
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
      case AUTH_FAILED -> failedSaying(REFUSED);
      case SESSION_ALREADY_LIVE -> failedSaying(SESSION_ALREADY_LIVE);
      // Present because the refusal is a Lockout, which is the record's own rule.
      case LOCKED_OUT -> failed(LockoutText.forA(saidIn, notAdmitted.lockedFor().orElseThrow()));
      case ENROLMENT_REQUIRED -> onEnrolmentRequired.accept(accountName.getText());
    }
  }

  /** What this window says about a refusal, from the key of it: everything but the Lockout. */
  private void failedSaying(String key) {
    failed(saidIn.say(key));
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
    // Changing language mid-attempt would redraw the window out from under the answer coming back.
    language.setDisable(inFlight);
    if (inFlight) {
      message.setText("");
    }
  }
}
