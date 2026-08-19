package com.javafxlogin.core.vault;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import java.util.Arrays;

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
 * <p>The output length is not configurable and is not read from {@link Argon2Parameters}: it is
 * AES-256's key length, because that is what this key is for. What a deployment configures is the
 * cost — memory, time and lanes — which is the part that is about how expensive guessing should be.
 */
final class KeyEncryptionKey {

  private final byte[] material;

  private KeyEncryptionKey(byte[] material) {
    this.material = material;
  }

  static KeyEncryptionKey derivedFrom(char[] password, byte[] salt, Argon2Parameters parameters) {
    Argon2Function argon2id =
        Argon2Function.getInstance(
            parameters.memoryKib(),
            parameters.iterations(),
            parameters.parallelism(),
            AesGcm.KEY_BYTES,
            Argon2.ID);
    byte[] passwordBytes = Utf8.bytesOf(password);
    try {
      return new KeyEncryptionKey(argon2id.hash(passwordBytes, salt).getBytes());
    } finally {
      Arrays.fill(passwordBytes, (byte) 0);
    }
  }

  byte[] material() {
    return material;
  }

  /** Overwrites the key. Every derivation here is used once, inside one request, and then gone. */
  void destroy() {
    Arrays.fill(material, (byte) 0);
  }

  @Override
  public String toString() {
    return "KeyEncryptionKey[redacted]";
  }
}
