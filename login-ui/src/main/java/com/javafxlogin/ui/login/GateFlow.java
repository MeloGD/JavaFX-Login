package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.ServiceReachability;
import com.javafxlogin.core.ipc.ServiceUnreachableReason;
import com.javafxlogin.core.ipc.Unreachable;
import com.javafxlogin.core.session.Session;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * Whether this installation opens a window at all, which one it opens, and the language it opens in.
 *
 * <p>It is the whole of {@link LoginGate#protect}, kept apart from it for one reason: the language
 * is an argument here and is the machine's own there. A host product asks to be protected and gets
 * the machine's language, which is the only sensible thing to give it; the tests drive the same
 * flow in a language they name, so that what a screen says is asserted against the bundle it came
 * from rather than against whichever locale the machine running the suite happens to have.
 *
 * <p>Two questions are put to the service before anything is drawn, in this order: whether it can be
 * reached at all, and — only if it can — whether this installation still needs its Administrator.
 * Both are asked on a thread of their own. The first has a bounded wait behind it and the second
 * does not, and either way a window that had already been shown would be a window frozen in front of
 * somebody, which is the failure story 90 is about.
 */
final class GateFlow {

  private GateFlow() {}

  /**
   * Opens the first-run wizard while there is no Administrator, the login screen once there is, and
   * neither where the AuthenticationService could not be asked which of the two it should be.
   *
   * <p>Both windows are drawn in {@code language}, because nobody has authenticated in front of
   * either of them: the wizard creates the Account that would have a preference, and the login
   * screen is where the person whose preference it is has not yet said who they are. So is the
   * refusal, for the plainer reason that there is nobody it could be drawn for instead.
   *
   * <p>Returns as soon as it has asked. Nothing is on the stage until the answer comes back, which
   * is a fraction of a second on a machine where the service is there and at most
   * {@link com.javafxlogin.core.ipc.ServiceHandshake#PATIENCE} on one where it is not.
   */
  static void open(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language) {
    GateAttempt.make(
        "service-reachability",
        () -> whatToOpen(gate),
        opening -> open(opening, gate, stage, protectedFeature, language),
        saying -> ServiceUnreachableWindow.show(stage, language, saying));
  }

  private static void open(
      Opening opening,
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language) {
    switch (opening) {
      case Refuse refuse -> ServiceUnreachableWindow.show(stage, language, refuse.saying());
      case Wizard ignored ->
          FirstRunWindow.show(
              gate,
              stage,
              language,
              () -> LoginWindow.show(gate, stage, protectedFeature, language));
      case Login ignored -> LoginWindow.show(gate, stage, protectedFeature, language);
    }
  }

  /**
   * The service is asked whether it is there before it is asked anything else, and this application
   * refuses to start rather than guessing when it is not.
   *
   * <p>Guessing was the old behaviour and it was the wrong one twice over: it would put a wizard in
   * front of somebody on a machine that may well have an Administrator already, or a login screen in
   * front of somebody whose password nothing on the machine can verify. Story 90 asks for neither.
   */
  private static Opening whatToOpen(LoginGate gate) {
    ServiceReachability reachability = gate.reachability();
    if (reachability instanceof Unreachable unreachable) {
      return refuse(unreachable.reason());
    }
    try {
      return gate.firstRunNeeded() ? new Wizard() : new Login();
    } catch (ServiceUnreachableException e) {
      // The service answered the first question and not the second. Whatever happened in between,
      // what the person is looking at is a machine where nothing answered — which is what "not
      // running" says, and it is said with its remedy rather than as a bare "could not be reached".
      // Drawing a window and hoping is the one thing story 90 rules out.
      return refuse(ServiceUnreachableReason.NOT_RUNNING);
    }
  }

  private static Refuse refuse(ServiceUnreachableReason reason) {
    return new Refuse(ServiceUnreachableText.keyFor(reason));
  }

  /** Which of the three windows the answers add up to. */
  private sealed interface Opening permits Refuse, Wizard, Login {}

  /**
   * No window of this application's own, and a sentence instead.
   *
   * @param saying the key of what happened and what to do about it
   */
  private record Refuse(String saying) implements Opening {}

  private record Wizard() implements Opening {}

  private record Login() implements Opening {}
}
