package com.javafxlogin.core.account;

/**
 * A coarse estimate of how resistant an Account's password is to guessing.
 *
 * <p>Three bands and no number. The estimate is made from a precise score, and that score is
 * discarded the moment the band is chosen: a store that leaked with scores in it would rank every
 * Account by how cheap it is to attack, which is a shopping list an attacker would otherwise have
 * to build for themselves.
 *
 * <p>The band never refuses a password. It is shown to the person choosing one, and to the
 * Administrator looking for the Accounts worth nudging.
 */
public enum PasswordStrength {

  /** Guessable by an attacker who is only moderately patient. */
  WEAK,

  /** Not obviously guessable, without being a long or unusual password. */
  ACCEPTABLE,

  /** Resistant to guessing at the scale an offline attack on this store could reach. */
  STRONG
}
