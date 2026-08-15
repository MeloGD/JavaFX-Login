package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.session.SessionEndedReason;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link Sessions} makes of a SessionToken a client presented.
 *
 * <p>It is the registry's own vocabulary rather than the wire's: the service turns it into a
 * response, and what a client is told is that mapping's business. Keeping the two apart is what
 * lets every rule about when a Session is over be tested without a message in sight.
 */
public sealed interface SessionOutcome {

  /**
   * The token names the live Session, on the connection it was granted on.
   *
   * @param accountName whose Session it is, which is what a recorded event is written against
   * @param role the Role the Session was granted in, which is what an administrative request is
   *     checked against
   * @param expiresIn how long the Session has left before inactivity ends it, or empty where an
   *     Administrator has switched expiry off
   */
  record Live(String accountName, Role role, Optional<Duration> expiresIn)
      implements SessionOutcome {

    public Live {
      Objects.requireNonNull(accountName, "accountName");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(expiresIn, "expiresIn");
    }
  }

  /** The token names no live Session, for one of the reasons a client is allowed to be told. */
  record Ended(SessionEndedReason reason) implements SessionOutcome {

    public Ended {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
