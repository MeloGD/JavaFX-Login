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
   * Where a packaged installation puts the socket. The installer owns this path — it is the one
   * thing the two processes have to agree on — and the property below is how the pair is run by
   * hand on a development machine, where nothing is installed anywhere.
   */
  private static final String INSTALLED_SOCKET = "/run/javafx-login/authentication.sock";

  private static final String SOCKET_PROPERTY = "javafxlogin.socket";

  @Override
  public void start(Stage stage) {
    LoginGate.toService(socketPath()).protect(stage, FeatureView::load);
  }

  private static Path socketPath() {
    return Path.of(System.getProperty(SOCKET_PROPERTY, INSTALLED_SOCKET));
  }
}
