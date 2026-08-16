package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.DeniedReason;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The secret was not taken.
 *
 * <p>The reason is the service's own and says as little here as it does at the login screen: wrong,
 * expired, already used and against an Account that is waiting for nothing are one answer, because
 * telling them apart would sort names into the ones worth trying again. The exception is the same
 * exception the login screen has — an Account in Lockout is told so and told for how long, since a
 * person who is not told simply keeps typing.
 *
 * @param reason as far as the service told this client
 * @param lockedFor how long the Lockout has left, and present for {@link DeniedReason#LOCKED_OUT}
 *     alone
 */
public record EnrolmentRefused(DeniedReason reason, Optional<Duration> lockedFor)
    implements EnrolmentOutcome {

  public EnrolmentRefused {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(lockedFor, "lockedFor");
    if (lockedFor.isPresent() != (reason == DeniedReason.LOCKED_OUT)) {
      throw new IllegalArgumentException(
          "Only a Lockout says how long it lasts, and it always does: " + reason);
    }
  }

  /** The secret was not the one, and the screen is told no more than that. */
  public static EnrolmentRefused because(DeniedReason reason) {
    return new EnrolmentRefused(reason, Optional.empty());
  }

  /** The Account is in Lockout, and this is what is left of it. */
  public static EnrolmentRefused lockedFor(Duration remaining) {
    return new EnrolmentRefused(DeniedReason.LOCKED_OUT, Optional.of(remaining));
  }
}
