package com.javafxlogin.core.crypto;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import java.util.Arrays;

/**
 * The thirty-two bytes a password derives through Argon2id, which is what opens something rather
 * than what proves somebody typed the right thing.
 *
 * <p>This is the arithmetic behind two separate ideas, and it is written once so that raising the
 * cost of guessing raises it in both places. The SecretVault's KeyEncryptionKey is one of them: an
 * Operator's password derives the key that unwraps their copy of the DataKey. A Backup is the other:
 * the password an Administrator types at the moment of the export derives the key the file is
 * sealed under, which is the whole of what ADR-0006 leaves protecting a file that restores on any
 * machine.
 *
 * <p><b>Nothing here reads a stored authentication hash.</b> The salt is the one recorded beside the
 * thing this key opens, and never the salt inside a hash — a hash is what verifies a password, and
 * key material is what a password produces, and the two are not allowed to be the same bytes.
 *
 * <p>The output length is not configurable and is not read from {@link Argon2Parameters}: it is
 * AES-256's key length, because that is what this key is for. What a deployment configures is the
 * cost — memory, time and lanes — the part that is about how expensive guessing should be.
 */
public final class PasswordDerivedKey {

  private final byte[] material;

  private PasswordDerivedKey(byte[] material) {
    this.material = material;
  }

  /**
   * Derives the key a password opens something with.
   *
   * <p>Deliberately slow, which is the point of it: this is what stands between somebody holding a
   * copy of the file and somebody reading it.
   *
   * @param salt the one recorded beside whatever this key opens
   * @param parameters the cost recorded beside it too, so that a file written under one cost is
   *     still readable after the deployment raises it
   */
  public static PasswordDerivedKey from(
      char[] password, byte[] salt, Argon2Parameters parameters) {
    Argon2Function argon2id =
        Argon2Function.getInstance(
            parameters.memoryKib(),
            parameters.iterations(),
            parameters.parallelism(),
            AesGcm.KEY_BYTES,
            Argon2.ID);
    byte[] passwordBytes = Utf8.bytesOf(password);
    try {
      return new PasswordDerivedKey(argon2id.hash(passwordBytes, salt).getBytes());
    } finally {
      Arrays.fill(passwordBytes, (byte) 0);
    }
  }

  public byte[] material() {
    return material;
  }

  /** Overwrites the key. Every derivation here is used once, inside one request, and then gone. */
  public void destroy() {
    Arrays.fill(material, (byte) 0);
  }

  @Override
  public String toString() {
    return "PasswordDerivedKey[redacted]";
  }
}
