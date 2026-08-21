package com.javafxlogin.core.vault;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.crypto.PasswordDerivedKey;

/**
 * The key a password derives, and the only thing that unwraps an Operator's copy of the DataKey.
 *
 * <p>This is the load-bearing idea of ADR-0004, in one class. The Vault does not open because a
 * boolean said the password was right — that boolean is exactly what a patched binary would flip. It
 * opens because the password the Operator just typed derives, through Argon2id with a salt and
 * parameters of the Vault's own, the key that unwraps the DataKey. A build with the verification
 * ripped out of it still cannot produce these thirty-two bytes.
 *
 * <p><b>Nothing here reads the stored authentication hash.</b> The salt comes from the wrap row and
 * was never the salt inside that hash; the parameters come from the wrap row too. Separate does not
 * mean larger: both currently sit at the same numbers, because both sit at the OWASP minimums, and
 * either can be raised without touching the other.
 *
 * <p>The derivation itself is {@link PasswordDerivedKey}, which a Backup uses as well. The name
 * stays here because the idea is the Vault's: these bytes are what an Operator's password does to
 * their wrapped copy of the DataKey, and calling that by the general name would lose which of the
 * two things a password can produce this one is.
 */
final class KeyEncryptionKey {

  private final PasswordDerivedKey derived;

  private KeyEncryptionKey(PasswordDerivedKey derived) {
    this.derived = derived;
  }

  static KeyEncryptionKey derivedFrom(char[] password, byte[] salt, Argon2Parameters parameters) {
    return new KeyEncryptionKey(PasswordDerivedKey.from(password, salt, parameters));
  }

  byte[] material() {
    return derived.material();
  }

  /** Overwrites the key. Every derivation here is used once, inside one request, and then gone. */
  void destroy() {
    derived.destroy();
  }

  @Override
  public String toString() {
    return "KeyEncryptionKey[redacted]";
  }
}
