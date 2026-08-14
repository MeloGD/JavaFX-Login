package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The entry point a host product calls to obtain a Session. It is the only part of this system a
 * host product is required to know about.
 *
 * <p>Integrating with it is two lines:
 *
 * <pre>{@code
 * LoginGate.toService(socketPath).protect(stage, () -> myFeatureView());
 * }</pre>
 *
 * <p>The view is a {@link Supplier} rather than a view, so that nothing behind the gate is built
 * until someone is admitted, and it is a {@link Parent} rather than anything of the host's, so that
 * the gate knows nothing about the feature it protects.
 *
 * <p>It is an interface because a test drives the windows against a fake one. That is the seam the
 * UI tests run at: no service, no socket and no crypto, so a broken window cannot arrive disguised
 * as a broken hash. What an implementation owes is one method — offer a name and a password, get a
 * Session or a refusal — and everything else here is the same for every implementation.
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
   * Shows the login window on {@code stage} and, once an Operator is admitted, closes it and opens
   * {@code protectedFeature} on a stage of its own.
   *
   * <p>This is the whole of the flow, and it is the same whichever gate is behind it, which is why
   * it is given rather than left to each implementation to get right.
   *
   * <p>Must be called on the JavaFX application thread.
   */
  default void protect(Stage stage, Supplier<Parent> protectedFeature) {
    LoginWindow.show(this, stage, protectedFeature);
  }
}
