package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * Creates the single Administrator. Accepted only when no Administrator exists.
 *
 * <p>This is the only way the first Account comes into existence: the system ships zero Accounts
 * and issues no recovery key, so the password given here cannot be recovered.
 *
 * @param administratorName the name the person installing typed, with nothing prefilled or
 *     suggested
 * @param password not retained by the service beyond hashing it
 */
public record Bootstrap(String administratorName, char[] password) implements Request {

  public Bootstrap {
    Objects.requireNonNull(administratorName, "administratorName");
    Objects.requireNonNull(password, "password");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "Bootstrap[redacted]";
  }
}
