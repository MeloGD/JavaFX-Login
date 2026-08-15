package com.javafxlogin.core.ipc;

/**
 * Why authentication was refused, as far as the client is told.
 *
 * <p>The set grows only when a ticket adds a refusal the client must act on differently — a locked
 * Account and an Account awaiting enrolment both will, because each sends the person somewhere
 * other than the wrong-password message. Everything else stays {@link #AUTH_FAILED}, because a
 * reason the client can read is a reason an attacker can read.
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
  SESSION_ALREADY_LIVE
}
