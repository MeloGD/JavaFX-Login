package com.javafxlogin.ui.login;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The Session is still there.
 *
 * @param expiresIn how long it has left without further activity, as the service measured it — or
 *     empty where an Administrator has switched expiry off and there is nothing to wait for
 */
public record SessionContinues(Optional<Duration> expiresIn) implements SessionStatus {

  public SessionContinues {
    Objects.requireNonNull(expiresIn, "expiresIn");
  }
}
