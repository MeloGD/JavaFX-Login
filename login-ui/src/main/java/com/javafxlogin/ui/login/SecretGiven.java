package com.javafxlogin.ui.login;

import java.util.Objects;

/**
 * The secret that was asked for, decrypted by the service for this request and no longer.
 *
 * <p>It arrives as a {@code char[]} so that a host product can overwrite it once it has connected
 * with it. Nothing here does that on the product's behalf: the array is the product's from the
 * moment it is handed over, and how long it keeps it is the one part of this the gate cannot decide.
 *
 * @param secret the secret as the SecretVault held it
 */
public record SecretGiven(char[] secret) implements SecretOutcome {

  public SecretGiven {
    Objects.requireNonNull(secret, "secret");
  }

  /** Never logged: the secret is the whole of what this carries. */
  @Override
  public String toString() {
    return "SecretGiven[redacted]";
  }
}
