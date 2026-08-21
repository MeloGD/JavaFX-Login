package com.javafxlogin.feature;

import com.javafxlogin.ui.login.LoginGate;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * The reference integration: everything a host product has to write in order to put its feature
 * behind this login.
 *
 * <p>There is one line of it. The product names where the AuthenticationService listens, hands over
 * a view, and is done: no Account, no password, no hash and no socket appear anywhere in it, which
 * is the whole promise of the {@link LoginGate}.
 *
 * <p>The JVM is pointed at {@link ProtectedFeatureLauncher} rather than at this class, for the
 * reason recorded there.
 */
public final class ProtectedFeatureApplication extends Application {

  /**
   * Where a packaged installation puts the socket: the path the shipped {@code .socket} unit
   * declares, and the one thing the two processes have to agree on. The property below is how the
   * pair is run by hand on a development machine, where nothing is installed anywhere.
   *
   * <p>Nothing at run time reconciles the two. A client pointed at a path systemd does not listen
   * on connects to nothing, and under socket activation "nothing answered" is also what a service
   * that has simply never been started looks like — so the disagreement would reach a person as
   * "the AuthenticationService is not running" on a machine where it is installed and well.
   * {@code TheInstalledSocketIsTheOneSystemdListensOnTest} is what keeps them together.
   */
  private static final String INSTALLED_SOCKET = "/run/javafx-login-authd.sock";

  private static final String SOCKET_PROPERTY = "javafxlogin.socket";

  /** The installed path, for the test that holds it against the unit file. */
  static Path installedSocket() {
    return Path.of(INSTALLED_SOCKET);
  }

  /**
   * The Session is offered and this product has nothing to do with it yet. A product that later
   * wants to log out, or to ask the SecretVault for a secret, takes it here.
   */
  @Override
  public void start(Stage stage) {
    LoginGate.toService(socketPath()).protect(stage, session -> FeatureView.load());
  }

  private static Path socketPath() {
    return Path.of(System.getProperty(SOCKET_PROPERTY, INSTALLED_SOCKET));
  }
}
