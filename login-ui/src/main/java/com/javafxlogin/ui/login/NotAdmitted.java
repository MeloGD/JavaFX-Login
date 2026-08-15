package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.DeniedReason;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Nobody was admitted.
 *
 * <p>The reason is the service's own, carried through rather than interpreted here, and it is
 * almost always the one that says nothing: a wrong password, an Account that does not exist and an
 * Account holding another Role are one refusal, because the login screen must not become an oracle
 * for the account list. Two of them say more — a Session already being live, which says nothing
 * about any Account and everything about why retyping a password would not help, and a Lockout,
 * which says how long there is to wait so that nobody stands there guessing.
 *
 * @param reason as far as the service told this client
 * @param lockedFor how long the Lockout has left, and present for {@link DeniedReason#LOCKED_OUT}
 *     alone — the window has a number to say because the service sent one
 */
public record NotAdmitted(DeniedReason reason, Optional<Duration> lockedFor) implements Admission {

  public NotAdmitted {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(lockedFor, "lockedFor");
    if (lockedFor.isPresent() != (reason == DeniedReason.LOCKED_OUT)) {
      throw new IllegalArgumentException(
          "Only a Lockout says how long it lasts, and it always does: " + reason);
    }
  }

  /** A refusal that says nothing but why, which is every refusal except a Lockout. */
  public static NotAdmitted because(DeniedReason reason) {
    return new NotAdmitted(reason, Optional.empty());
  }

  /** The Account is in Lockout, and this is what is left of it. */
  public static NotAdmitted lockedFor(Duration remaining) {
    return new NotAdmitted(DeniedReason.LOCKED_OUT, Optional.of(remaining));
  }
}
