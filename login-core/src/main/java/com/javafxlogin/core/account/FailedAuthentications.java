package com.javafxlogin.core.account;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What the CredentialStore remembers about one Account's failed authentications: how many have
 * happened in a row, and the moment it stops being refused if it has been refused at all.
 *
 * <p>The second field present is the Lockout of the glossary. The first on its own is only how
 * close the Account is to one, which is why the two travel together: reading the count without the
 * moment, or the moment without the count, would be reading half of a decision.
 *
 * @param inARow failures since the last authentication that succeeded, or since an Administrator
 *     cleared them
 * @param refusedUntil when the Lockout is over, or empty where the Account is not in one
 */
public record FailedAuthentications(int inARow, Optional<Instant> refusedUntil) {

  public FailedAuthentications {
    Objects.requireNonNull(refusedUntil, "refusedUntil");
    if (inARow < 0) {
      throw new IllegalArgumentException("Failures are counted from none, not from " + inARow);
    }
  }

  /** Nothing held against the Account: what a fresh one has, and what clearing a Lockout leaves. */
  public static FailedAuthentications none() {
    return new FailedAuthentications(0, Optional.empty());
  }
}
