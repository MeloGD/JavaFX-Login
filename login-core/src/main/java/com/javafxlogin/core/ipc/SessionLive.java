package com.javafxlogin.core.ipc;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The Session is still there, and this is how long it has left.
 *
 * <p>The remaining time is what the SessionGuard arms itself with, so that the guard never has to
 * work out when a Session is due — it is told, every time it says anything at all, and asks again
 * when the service says the time is up. A guard that computed the moment itself would be a guard
 * that could be patched into computing a later one.
 *
 * @param expiresIn how long the Session has left without further activity, or empty where an
 *     Administrator has switched expiry off and there is nothing to wait for
 */
public record SessionLive(Optional<Duration> expiresIn) implements Response {

  public SessionLive {
    Objects.requireNonNull(expiresIn, "expiresIn");
  }
}
