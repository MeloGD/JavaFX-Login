package com.javafxlogin.core.ipc;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Authentication was refused.
 *
 * <p>Two attempts that failed for different reasons produce equal Denied values, which is the
 * point: the login screen must not become an oracle for which Accounts exist.
 *
 * <p>A Lockout is the one refusal that carries anything besides its reason, and it carries the one
 * thing story 43 asks for — how long the Account has left to wait. A person not told that keeps
 * guessing at an Account that has stopped listening. Nothing else is ever added here: a refusal
 * that explained itself would explain itself to whoever is doing the guessing.
 *
 * @param reason as far as the client is told
 * @param lockedFor how long the Lockout has left, and present for {@link DeniedReason#LOCKED_OUT}
 *     alone
 */
public record Denied(DeniedReason reason, Optional<Duration> lockedFor) implements Response {

  public Denied {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(lockedFor, "lockedFor");
    if (lockedFor.isPresent() != (reason == DeniedReason.LOCKED_OUT)) {
      throw new IllegalArgumentException(
          "Only a Lockout says how long it lasts, and it always does: " + reason);
    }
  }

  /** A refusal that says nothing but why, which is every refusal except a Lockout. */
  public static Denied because(DeniedReason reason) {
    return new Denied(reason, Optional.empty());
  }

  /** The Account is in Lockout, and this is what is left of it. */
  public static Denied lockedFor(Duration remaining) {
    return new Denied(DeniedReason.LOCKED_OUT, Optional.of(remaining));
  }
}
