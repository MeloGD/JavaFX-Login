package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.Enrolment;
import com.javafxlogin.core.account.EnrolmentSecret;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.session.SessionClock;
import com.javafxlogin.core.store.CredentialStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Every rule about an Account that is waiting for somebody to give it a password.
 *
 * <p>It exists so that one component holds all of ASVS 5.0 §6.4.6 rather than having it spread
 * across the service's request handlers: an Administrator brings an Account into being, and what
 * that Account authenticates with is decided by whoever will use it, out of a secret this component
 * generates, hashes, expires and consumes.
 *
 * <p>The state lives in the CredentialStore, for the reason {@link Lockouts}' does: the service
 * stops after five idle minutes, and a secret held in memory would be one that a restart cancels
 * halfway through somebody's first morning.
 *
 * <p>Timed against the wall clock alone, again as a Lockout is, and guarded the same way — a secret
 * that claims to have longer left than a secret is allowed to have was written before the machine's
 * clock moved backwards, and is read as expired rather than as valid until whatever date the wrong
 * clock implies. An Administrator re-issues it, which costs a conversation.
 *
 * <p>Not thread-safe on its own: it is used from inside the service's monitor, which serialises
 * every request.
 */
final class Enrolments {

  private final CredentialStore store;
  private final SessionClock clock;
  private final SecureRandom random;

  Enrolments(CredentialStore store, SessionClock clock, SecureRandom random) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.random = Objects.requireNonNull(random, "random");
  }

  /**
   * A secret on its way to an Administrator, and when it stops working.
   *
   * <p>It carries the {@link EnrolmentSecret} rather than the text of one. The text is a String that
   * prints itself, and this record would then print it too — in a stack trace, in a debug line, in
   * whatever a later build hands it to. Holding the type that redacts itself is what makes that
   * impossible by shape rather than by everybody remembering.
   *
   * @param secret the secret itself, which the caller asks for the text of once
   * @param expiresAt computed from what the deployment has configured now, which is why it is said
   *     here and not stored — an Administrator who shortens the lifetime shortens this too
   */
  record Issued(EnrolmentSecret secret, Instant expiresAt) {}

  /** Creates an Account with no password, and the one-time secret that will give it one. */
  Issued create(String accountName, Role role) {
    EnrolmentSecret secret = EnrolmentSecret.generate(random);
    store.insertAwaitingEnrolment(accountName, role, enrolmentOf(secret));
    return issuedNow(secret);
  }

  /**
   * Takes an Account's password away and issues a secret in its place.
   *
   * <p>The same operation whether it is a reset or a re-issue, because it is the same operation. An
   * Account that had a password is recorded as having had it taken away, and its holder is told at
   * their next login; one that was already awaiting enrolment has nothing to be told about, and
   * whatever it was already owed is left alone.
   */
  Issued issueFor(Account account) {
    EnrolmentSecret secret = EnrolmentSecret.generate(random);
    Optional<Instant> resetAt =
        account.isAwaitingEnrolment() ? Optional.empty() : Optional.of(clock.wallTime());
    store.awaitEnrolment(account.name(), enrolmentOf(secret), resetAt);
    return issuedNow(secret);
  }

  /**
   * Whether what somebody typed is the secret this Account is waiting on, and is still in time.
   *
   * <p>Everything that is not that is one answer: a secret for another Account, a secret that has
   * been used, one that has expired, text that is not a secret at all, and an Account that is not
   * awaiting enrolment. The caller cannot tell them apart because the person at the screen must not
   * be able to either.
   */
  boolean accepts(String accountName, char[] offered) {
    Optional<Enrolment> enrolment = store.enrolmentOf(accountName);
    if (enrolment.isEmpty() || hasExpired(enrolment.get())) {
      return false;
    }
    return EnrolmentSecret.parse(offered)
        .map(secret -> secret.matches(enrolment.get().secretHash()))
        .orElse(false);
  }

  /**
   * Records the password whoever held the secret chose, and consumes the secret in the same breath.
   * From here the Account authenticates like any other, and the secret is worth nothing.
   */
  void completedBy(String accountName, String passwordHash, PasswordStrength strength) {
    store.completeEnrolment(accountName, passwordHash, strength);
  }

  /**
   * What this Account's holder is owed being told about a reset they did not ask for.
   *
   * <p>Read at the moment somebody proves they hold the Account, which is the only moment it can be
   * said to the right person: an Administrator initiating a reset knows they did it, and a screen
   * showing the notice to whoever comes past would be telling the machine's room rather than the
   * Operator.
   *
   * <p>Reading it does not spend it — see {@link #declaredTo}. It is said on every admission until
   * somebody says they have read it, because a notice that was sent is not a notice that arrived.
   */
  Optional<Instant> resetToDeclareFor(String accountName) {
    return store.passwordResetAt(accountName);
  }

  /**
   * The person holding the Account has read the notice, so it is over.
   *
   * <p>This is the only thing that ends it. The alternative — forgetting it as it is handed to the
   * client — spends the one copy on a message that may never have been drawn: a client that dies
   * between being granted a Session and painting a window would leave the Operator never told,
   * about the one event in this system that exists to be noticed by them and nobody else.
   */
  void declaredTo(String accountName) {
    store.forgetPasswordReset(accountName);
  }

  private Enrolment enrolmentOf(EnrolmentSecret secret) {
    return new Enrolment(secret.hashed(), clock.wallTime());
  }

  private Issued issuedNow(EnrolmentSecret secret) {
    return new Issued(secret, clock.wallTime().plus(store.enrolmentSecretLastsFor()));
  }

  /**
   * Whether the secret has run out — or claims to have longer left than one is allowed to have,
   * which means the machine's clock was set backwards since it was issued. Both are read as expired,
   * for the reason ADR-0010 gives about a Lockout: whoever can move the machine's clock can rewrite
   * this file directly, and refusing to be generous with a clock that is lying costs nothing.
   */
  private boolean hasExpired(Enrolment enrolment) {
    Duration lastsFor = store.enrolmentSecretLastsFor();
    Duration left = Duration.between(clock.wallTime(), enrolment.issuedAt().plus(lastsFor));
    return left.isZero() || left.isNegative() || left.compareTo(lastsFor) > 0;
  }
}
