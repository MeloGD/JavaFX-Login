package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The login window, what replaces it once someone is admitted, and what puts it back when their
 * Session ends.
 *
 * <p>The stage is the same one throughout. Someone admitted, and then returned by an expiry or by
 * logging out, is looking at one application that changed what it was showing rather than at
 * windows appearing and disappearing around them — and the window they are handed back to is loaded
 * fresh, so nothing they typed an hour ago is still in it.
 *
 * <p>The enrolment screen shares that stage too, and hands it back the same way. An Account with no
 * password is not a wrong password, so being sent there is not being refused: it is this
 * application showing the one screen where the code somebody was given is worth something.
 *
 * <p>This is also where issue #13's two languages meet. Everything shown on this stage is drawn in
 * the language nobody had to authenticate to choose — the machine's, or whatever the selector was
 * set to — and everything opened by an admission is drawn in the LanguagePreference of the Account
 * that was admitted. The login screen does not keep that language when it comes back: the next
 * person at this machine is not the last one, and a login screen that had learned somebody's
 * language would be telling them so.
 */
final class LoginWindow {

  private static final String FXML = "login-window.fxml";

  private static final String TITLE = "login.title";

  /** Nothing to say before anybody has done anything, which is what a fresh window says. */
  private static final String NOTHING = "";

  private LoginWindow() {}

  static void show(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language) {
    show(gate, stage, protectedFeature, language, NOTHING);
  }

  /**
   * @param saying the key of what to tell the person before they have done anything — why they are
   *     back at this window, where they arrived at it by a Session ending or by finishing an
   *     enrolment. It is a key and not a sentence because the window it was decided in may have
   *     been drawn in another language than this one is.
   */
  private static void show(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language,
      String saying) {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(protectedFeature, "protectedFeature");
    Objects.requireNonNull(language, "language");

    GateWindow window = GateWindow.loadedFrom(FXML, language);
    window
        .controller(LoginController.class)
        .admitWith(
            gate,
            language,
            admitted -> hold(gate, stage, protectedFeature, language, admitted),
            admitted -> administer(gate, stage, protectedFeature, language, admitted),
            accountName -> enrol(gate, stage, protectedFeature, language, accountName),
            chosen -> chooseTheLanguage(gate, stage, protectedFeature, chosen),
            saying);
    window.showOn(stage, language.say(TITLE));
  }

  /**
   * Somebody said which language they read, so this window is drawn again in it.
   *
   * <p>It comes back saying nothing. Whatever was on it was decided in the language they have just
   * told this application they do not read, and a screen that changed language except for one
   * sentence would be a screen still speaking the old one.
   */
  private static void chooseTheLanguage(
      LoginGate gate, Stage stage, Function<Session, Parent> protectedFeature, Locale chosen) {
    show(gate, stage, protectedFeature, InterfaceLanguage.of(chosen), NOTHING);
  }

  /**
   * Puts the enrolment screen on the same stage, and puts the login window back when it is done.
   *
   * <p>Whether the enrolment succeeded or the person gave up and closed nothing, they end where
   * they started: at the login screen, with a sentence saying which of the two happened. It is the same
   * language throughout: nobody has authenticated, so there is no Account whose preference could
   * have anything to say about it.
   */
  private static void enrol(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language,
      String accountName) {
    EnrolmentWindow.show(
        gate,
        stage,
        language,
        accountName,
        saying -> show(gate, stage, protectedFeature, language, saying));
  }

  /**
   * Opens the administration panel, and closes the login window behind it.
   *
   * <p>The same arrangement as an admitted Operator's window, in the same order and for the same
   * reason — but with nothing of the host product in it. An Administrator does not reach the
   * ProtectedFeature, so its view is not built here at all; what is passed on is only what puts the
   * login screen back when the Session ends.
   */
  private static void administer(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language,
      Admitted admitted) {
    AdministrationWindow.open(
        gate,
        admitted,
        theirs(admitted, language),
        saying -> show(gate, stage, protectedFeature, language, saying));
    stage.close();
  }

  /**
   * Opens the window the Operator works in, and closes the login window behind it.
   *
   * <p>That order is deliberate, in both directions: closing the only window a JavaFX application
   * has shown ends the toolkit, so whichever window is arriving is shown before the one it replaces
   * goes.
   */
  private static void hold(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language,
      Admitted admitted) {
    SessionWindow.open(
        gate,
        admitted,
        theirs(admitted, language),
        protectedFeature.apply(admitted.session()),
        saying -> show(gate, stage, protectedFeature, language, saying));
    stage.close();
  }

  /**
   * The language whoever has just been admitted reads: their Account's own, where they have one.
   *
   * <p>An Account that has said nothing keeps whatever was being read at the login screen, which is
   * the machine's own or whatever the selector was set to. That is what a deployment nobody has
   * been asked about looks like, and it is also the more useful answer: somebody who has just
   * chosen a language at the selector meant it.
   */
  private static InterfaceLanguage theirs(Admitted admitted, InterfaceLanguage atTheLoginScreen) {
    return admitted.languagePreference().map(InterfaceLanguage::of).orElse(atTheLoginScreen);
  }
}
