package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The entry point a host product calls to obtain a Session. It is the only part of this system a
 * host product is required to know about.
 *
 * <p>Integrating with it is two lines:
 *
 * <pre>{@code
 * LoginGate.toService(socketPath).protect(stage, session -> myFeatureView());
 * }</pre>
 *
 * <p>The view arrives as a function rather than as a view, so that nothing behind the gate is built
 * until someone is admitted, and what it is handed is the {@link Session} that admitting them
 * produced — a host that has no use for it yet can ignore the argument, and one that later wants to
 * log out or reach a secret already has the thing that names the Session. What it returns is a
 * {@link Parent} and nothing of the host's, so that the gate knows nothing about the feature it
 * protects.
 *
 * <p>It is an interface because a test drives the windows against a fake one. That is the seam the
 * UI tests run at: no service, no socket and no crypto, so a broken window cannot arrive disguised
 * as a broken hash. What an implementation owes is the whole of a client's conversation with the
 * AuthenticationService — which window to open, how the first Account comes into existence, and who
 * is admitted — and everything else here is the same for every implementation.
 */
public interface LoginGate {

  /**
   * The gate a shipped product uses: every attempt crosses the socket to the AuthenticationService,
   * which is the only party that can verify a password.
   *
   * @param socketPath where the AuthenticationService listens
   */
  static LoginGate toService(Path socketPath) {
    return new ServiceLoginGate(socketPath);
  }

  /**
   * Offers a name and a password on behalf of someone asking to reach the ProtectedFeature.
   *
   * <p>Blocks: verifying a password is deliberately slow, so this must not be called on the
   * JavaFX application thread.
   *
   * @return the Session, or empty if access was refused. The refusal carries no reason, because
   *     the service does not give one — a reason a client could read is a reason an attacker could
   *     read.
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all,
   *     which is not a refusal and must not be shown as one
   */
  Optional<Session> admit(String accountName, char[] password);

  /**
   * Whether this installation is still waiting for its single Administrator, which is what decides
   * whether a person sees the first-run wizard or the login screen.
   *
   * <p>The answer says nothing about who the Administrator is or would be — only that there is not
   * one yet, which is what a fresh install shows the moment it opens a window anyway.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  boolean firstRunNeeded();

  /**
   * Offers a name and a password for the single Administrator.
   *
   * <p>Being told the first run is needed is not being allowed to run it: the service accepts this
   * only from a peer that administers the machine, and only while no Administrator exists. Both
   * refusals come back as a {@link FirstRunRefused} rather than being decided here, because a
   * client that decided them could be patched into deciding otherwise.
   *
   * <p>Blocks: the password is hashed on the other side, which is deliberately slow, so this must
   * not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  FirstRunOutcome createAdministrator(String administratorName, char[] password);

  /**
   * Opens whichever window this installation needs on {@code stage} — the first-run wizard while
   * there is no Administrator, the login screen once there is — and, once an Operator is admitted,
   * closes it and opens the view {@code protectedFeature} builds on a stage of its own.
   *
   * <p>This is the whole of the flow, and it is the same whichever gate is behind it, which is why
   * it is given rather than left to each implementation to get right.
   *
   * <p>Must be called on the JavaFX application thread. Asking which window to open is one round
   * trip with no hashing behind it, and it happens before anything has been drawn, so it is made
   * here rather than off the thread: there is no window yet to freeze.
   */
  default void protect(Stage stage, Function<Session, Parent> protectedFeature) {
    if (theWizardIsNeeded()) {
      FirstRunWindow.show(this, stage, () -> LoginWindow.show(this, stage, protectedFeature));
      return;
    }
    LoginWindow.show(this, stage, protectedFeature);
  }

  /**
   * A service that cannot be asked gets the login window, whose first attempt says plainly that it
   * could not be reached. Guessing the other way would put a wizard in front of someone on a
   * machine that may well have an Administrator already.
   */
  private boolean theWizardIsNeeded() {
    try {
      return firstRunNeeded();
    } catch (ServiceUnreachableException e) {
      return false;
    }
  }
}
