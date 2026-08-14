package com.javafxlogin.core.account;

/**
 * The single capability set attached to an Account. Roles are mutually exclusive and an Account
 * never holds both.
 */
public enum Role {

  /** The single Account that manages other Accounts and application configuration. */
  ADMINISTRATOR,

  /** An Account allowed to reach the ProtectedFeature. */
  OPERATOR
}
