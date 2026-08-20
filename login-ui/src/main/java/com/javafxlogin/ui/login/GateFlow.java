package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * Which window this installation opens, and the language it opens in.
 *
 * <p>It is the whole of {@link LoginGate#protect}, kept apart from it for one reason: the language
 * is an argument here and is the machine's own there. A host product asks to be protected and gets
 * the machine's language, which is the only sensible thing to give it; the tests drive the same
 * flow in a language they name, so that what a screen says is asserted against the bundle it came
 * from rather than against whichever locale the machine running the suite happens to have.
 */
final class GateFlow {

  private GateFlow() {}

  /**
   * Opens the first-run wizard while there is no Administrator, and the login screen once there is.
   *
   * <p>Both are drawn in {@code language}, because nobody has authenticated in front of either of
   * them: the wizard creates the Account that would have a preference, and the login screen is
   * where the person whose preference it is has not yet said who they are.
   */
  static void open(
      LoginGate gate,
      Stage stage,
      Function<Session, Parent> protectedFeature,
      InterfaceLanguage language) {
    if (theWizardIsNeeded(gate)) {
      FirstRunWindow.show(
          gate, stage, language, () -> LoginWindow.show(gate, stage, protectedFeature, language));
      return;
    }
    LoginWindow.show(gate, stage, protectedFeature, language);
  }

  /**
   * A service that cannot be asked gets the login window, whose first attempt says plainly that it
   * could not be reached. Guessing the other way would put a wizard in front of someone on a
   * machine that may well have an Administrator already.
   */
  private static boolean theWizardIsNeeded(LoginGate gate) {
    try {
      return gate.firstRunNeeded();
    } catch (ServiceUnreachableException e) {
      return false;
    }
  }
}
