package com.javafxlogin.core.ipc;

/**
 * Why authentication was refused, as far as the client is told.
 *
 * <p>The set grows only when a ticket adds a refusal the client must act on differently — a locked
 * Account and an Account awaiting enrolment both will, because each sends the person somewhere other
 * than the wrong-password message. Everything else stays {@link #AUTH_FAILED}, because a reason the
 * client can read is a reason an attacker can read.
 */
public enum DeniedReason {

    /**
     * Authentication failed. Says nothing about whether the Account exists, whether the password was
     * wrong, or which of the two it was — the real reason goes to the audit log instead.
     */
    AUTH_FAILED
}
