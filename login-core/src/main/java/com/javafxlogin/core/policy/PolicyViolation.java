package com.javafxlogin.core.policy;

/**
 * Why an Account name or a password was refused.
 *
 * <p>Unlike a failed authentication, a refusal here may be specific: the person reading it is the
 * one choosing the value, and a rule they cannot see is a rule they retype against blindly. Each
 * constant is a reason the UI turns into a sentence, which is why the set names the rule rather
 * than the message — the wording, and its translation, belong to the client.
 *
 * <p>The order of declaration is the order violations are reported in, so the UI can print them as
 * it receives them: what is wrong with the name first, then what is wrong with the password.
 */
public enum PolicyViolation {

  /** The name was empty, or had nothing left in it once separators were taken out. */
  ACCOUNT_NAME_BLANK,

  /**
   * The name is one an attacker would guess first. See ADR-0002: because the account list cannot be
   * read, a predictable name is the one thing that hands an entry of it back.
   */
  ACCOUNT_NAME_BLOCKED,

  /** Shorter than the twelve characters the policy requires. */
  PASSWORD_TOO_SHORT,

  /** Longer than the sixty-four characters the policy allows. */
  PASSWORD_TOO_LONG,

  /** No uppercase letter. */
  PASSWORD_WITHOUT_UPPERCASE,

  /** No number. */
  PASSWORD_WITHOUT_NUMBER,

  /** No special character. */
  PASSWORD_WITHOUT_SPECIAL_CHARACTER,

  /**
   * The password appears in the breach list bundled with the application. It is already in the
   * corpus an offline attack starts from, so its length and shape buy nothing.
   */
  PASSWORD_BREACHED
}
