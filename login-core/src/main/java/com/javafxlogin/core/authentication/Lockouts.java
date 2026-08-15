package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.FailedAuthentications;
import com.javafxlogin.core.account.LockoutPolicy;
import com.javafxlogin.core.session.SessionClock;
import com.javafxlogin.core.store.CredentialStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Every rule about an Account that has failed authentication too often, and where that state lives.
 *
 * <p>It lives in the CredentialStore, which is the whole point of this component. The
 * AuthenticationService stops after five idle minutes, so a counter held in memory would be one an
 * attacker clears by waiting rather than by guessing right; and the store is the service's own
 * file, so an Operator cannot delete the record of their own attempts. Story 89 asks for exactly
 * this, and it is why nothing here caches.
 *
 * <p>Timed against the wall clock alone, unlike a Session. The clock that cannot be moved is a
 * count from an origin the process chose, so it means nothing to the process that reads the store
 * next — a Lockout that must outlive the service can only be written as a moment the machine
 * agrees is a moment. What that costs, and why it costs less than it looks, is ADR-0010.
 *
 * <p>Not thread-safe on its own: it is used from inside the service's monitor, which serialises
 * every request, and the store beneath it holds a single JDBC connection anyway.
 */
final class Lockouts {

  private final CredentialStore store;
  private final SessionClock clock;

  Lockouts(CredentialStore store, SessionClock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * How long the named Account is refused for, or empty where it is not refused at all — which is
   * also the answer for an Account that does not exist.
   *
   * <p>A Lockout that has run out is forgotten here rather than left to be noticed again, so that
   * the Account starts from no failures at all: the next wrong password is a first wrong password
   * and not the one that locks it straight back.
   */
  Optional<Duration> refusalOf(String accountName) {
    Optional<Instant> refusedUntil =
        store.failedAuthenticationsOf(accountName).flatMap(FailedAuthentications::refusedUntil);
    if (refusedUntil.isEmpty()) {
      return Optional.empty();
    }
    Duration remaining = Duration.between(clock.wallTime(), refusedUntil.get());
    if (hasRunOut(remaining) || outlastsWhatWasConfigured(remaining)) {
      store.recordFailedAuthentications(accountName, FailedAuthentications.none());
      return Optional.empty();
    }
    return Optional.of(remaining);
  }

  /**
   * Records that an authentication against the named Account failed, and answers with the Lockout
   * it caused, if it caused one.
   *
   * <p>Called for an Account that exists and for no other. A name nobody holds is refused and
   * forgotten: remembering it would mean this file growing a row for every string typed at the
   * login screen, and one of those strings will eventually be somebody's password typed into the
   * wrong box.
   */
  Optional<Duration> failed(String accountName) {
    LockoutPolicy policy = store.lockoutPolicy();
    int inARow = countFor(accountName) + 1;
    if (inARow < policy.failuresThatLock()) {
      store.recordFailedAuthentications(
          accountName, new FailedAuthentications(inARow, Optional.empty()));
      return Optional.empty();
    }
    store.recordFailedAuthentications(
        accountName,
        new FailedAuthentications(inARow, Optional.of(clock.wallTime().plus(policy.lastsFor()))));
    return Optional.of(policy.lastsFor());
  }

  /**
   * The Account authenticated: the failures behind it are forgotten.
   *
   * <p>Nothing is written where there was nothing to forget, so the ordinary case — someone typing
   * their password correctly — costs the store no write at all.
   */
  void succeeded(String accountName) {
    if (countFor(accountName) > 0) {
      store.recordFailedAuthentications(accountName, FailedAuthentications.none());
    }
  }

  /**
   * Forgets everything held against an Account, which is what an Administrator clearing a Lockout
   * does.
   *
   * @return whether there was an Account of that name to clear it for
   */
  boolean clear(String accountName) {
    if (store.failedAuthenticationsOf(accountName).isEmpty()) {
      return false;
    }
    store.recordFailedAuthentications(accountName, FailedAuthentications.none());
    return true;
  }

  private int countFor(String accountName) {
    return store
        .failedAuthenticationsOf(accountName)
        .map(FailedAuthentications::inARow)
        .orElse(0);
  }

  private static boolean hasRunOut(Duration remaining) {
    return remaining.isZero() || remaining.isNegative();
  }

  /**
   * Whether the Lockout claims to end further away than a Lockout is allowed to last, which means
   * the machine's clock was set backwards since it was written.
   *
   * <p>Read as over rather than as a Lockout running until whatever date the wrong clock implies:
   * the rule is meant to last minutes, and an Account refused for a year by a clock error is a
   * person this system has locked out of their own product. It costs nothing that was not already
   * gone, because setting the machine's clock takes the privileges of a MachineAdministrator — and
   * whoever holds those can read and rewrite this file directly.
   */
  private boolean outlastsWhatWasConfigured(Duration remaining) {
    return remaining.compareTo(store.lockoutPolicy().lastsFor()) > 0;
  }
}
