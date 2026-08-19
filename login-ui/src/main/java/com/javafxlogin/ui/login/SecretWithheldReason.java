package com.javafxlogin.ui.login;

/**
 * Why the SecretVault answered with nothing.
 *
 * <p>Unlike the reasons an authentication fails, these may be specific: whoever is asking holds a
 * Session this service granted, and each of these has a different remedy — a different name, an
 * enrolment, or a different Account entirely.
 */
public enum SecretWithheldReason {

  /** Nothing is kept in the Vault under that name. */
  NO_SUCH_SECRET,

  /**
   * This Account holds no wrapped copy of the DataKey, so no password of theirs opens the Vault. It
   * is what an Account provisioned before the Vault existed looks like, and what one whose password
   * an Administrator has taken away looks like until it is enrolled again. The remedy is an
   * enrolment.
   */
  NO_VAULT_ACCESS,

  /**
   * The Session is not an Operator's. The Administrator manages Accounts and configuration and does
   * not hold Vault access — least privilege, and stated no more strongly than that: ADR-0005 is
   * explicit that whoever holds the Administrator password can create an Operator and read
   * everything, leaving a record of having done so.
   */
  NOT_AN_OPERATOR,

  /** The Session was over by the time the request arrived, so there was nobody to answer. */
  SESSION_OVER
}
