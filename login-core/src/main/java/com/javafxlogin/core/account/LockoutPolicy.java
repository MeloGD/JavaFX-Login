package com.javafxlogin.core.account;

import java.time.Duration;
import java.util.Objects;

/**
 * How many failed authentications in a row put an Account into Lockout, and how long that Lockout
 * lasts.
 *
 * <p>Configuration, held in the CredentialStore beside the Accounts it is about, and read again on
 * every decision rather than remembered — the same rule the InactivityPeriod follows, and for the
 * same reason: a deployment that changes it changes what happens next rather than what happens
 * after a restart.
 *
 * <p>It says nothing about what an attacker gains offline. A Lockout slows someone typing at the
 * login screen and does nothing at all for a hash that has already been taken; that is Argon2id's
 * job, and confusing the two would be reading this policy as a strength it does not have.
 *
 * @param failuresThatLock how many failures in a row are a Lockout — the failure that reaches this
 *     number is the one that locks, so 5 means five wrong passwords and not six
 * @param lastsFor how long the Account is refused for once it has been locked
 */
public record LockoutPolicy(int failuresThatLock, Duration lastsFor) {

  public LockoutPolicy {
    Objects.requireNonNull(lastsFor, "lastsFor");
    if (failuresThatLock < 1) {
      throw new IllegalArgumentException(
          "An Account is locked out by some number of failures, not " + failuresThatLock);
    }
    if (lastsFor.isZero() || lastsFor.isNegative()) {
      throw new IllegalArgumentException("A Lockout lasts some time, not " + lastsFor);
    }
  }
}
