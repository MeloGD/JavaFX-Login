package com.javafxlogin.core.ipc;

/**
 * Why authentication was refused, as far as the client is told.
 *
 * <p>The set grows only when a ticket adds a refusal the client must act on differently — a locked
 * Account and an Account awaiting enrolment both have, because each sends the person somewhere other
 * than the wrong-password message. Everything else stays {@link #AUTH_FAILED}, because a reason the
 * client can read is a reason an attacker can read.
 */
public enum DeniedReason {

  /**
   * Authentication failed. Says nothing about whether the Account exists, whether the password was
   * wrong, or which of the two it was — the real reason goes to the audit log instead.
   */
  AUTH_FAILED,

  /**
   * A Session is already live on this machine, and it was kept. The person is told this rather than
   * being sent to retype a password that was never looked at: no Account was read to produce this
   * refusal, so it is an oracle for nothing — a Session being open is already visible to anyone who
   * can see the screen it is open on.
   */
  SESSION_ALREADY_LIVE,

  /**
   * The Account has failed authentication often enough to be in Lockout, and is refused whatever
   * password came with this attempt. The one refusal that says something about an Account: after
   * enough wrong guesses, this answer tells an attacker the name they were guessing at is a real
   * one. Story 43 asks for it anyway, and ADR-0010 records what it costs and why the trade is worth
   * making — an attacker who buys that answer pays for it in Argon2id verifications, locks the
   * Account they were after, and leaves an AuthenticationEvent behind saying so.
   */
  LOCKED_OUT,

  /**
   * The Account has no password: nobody has enrolled against it yet, or an Administrator took the
   * one it had away. The person is sent to the enrolment screen with the secret they were handed,
   * rather than being told a password they never chose was wrong.
   *
   * <p>The second refusal that says something about an Account, and it says less than the first. It
   * names a name as real and as unclaimed, and what an attacker does with that is offer secrets
   * against it — 128 bits of them, service-generated, against a store that counts every wrong one
   * towards the same Lockout a wrong password earns. Story 30 asks for the answer because the
   * alternative is a person retyping a password they were never given at a screen that will never
   * take one.
   */
  ENROLMENT_REQUIRED
}
