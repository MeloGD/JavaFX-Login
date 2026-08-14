package com.javafxlogin.core.session;

import java.util.Objects;

/**
 * The period during which an authenticated Operator may reach the ProtectedFeature.
 *
 * <p>It is what a host product receives from the LoginGate, and today it carries only the token
 * that names it to the AuthenticationService. A Session ends on logout, on inactivity, or when the
 * client that owns it disappears — the first two are the Session lifecycle ticket's work, and the
 * third needs nothing here, because the service watches the connection this Session was granted on
 * and the kernel closes that connection for a client that dies.
 *
 * @param token the opaque value that identifies this Session to the AuthenticationService
 */
public record Session(SessionToken token) {

  public Session {
    Objects.requireNonNull(token, "token");
  }
}
