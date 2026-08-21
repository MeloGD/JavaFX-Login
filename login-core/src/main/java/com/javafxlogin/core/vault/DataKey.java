package com.javafxlogin.core.vault;

import com.javafxlogin.core.crypto.AesGcm;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The single key that encrypts the SecretVault, shared by every Operator and never stored unwrapped.
 *
 * <p>Package-private, and that is the whole of how "the raw DataKey is never exposed through the
 * API" is enforced. Nothing outside this package can name this type, so no method anywhere can hand
 * one out, take one in, or accidentally grow a getter for its bytes. The service holds an {@link
 * UnlockedVault} for as long as a Session lasts and asks it for one secret at a time; the key itself
 * never crosses that line, in either direction.
 *
 * <p>It is mutable on purpose: {@link #destroy()} overwrites the bytes rather than dropping a
 * reference, so that a Session ending is a key gone rather than a key waiting for a garbage
 * collector. That is as far as a JVM allows anybody to go, and it is worth saying that it is not
 * far: nothing here can stop the operating system paging those bytes to disk, and a
 * MachineAdministrator who can read a core dump was never in the threat model (ADR-0001).
 */
final class DataKey {

  private final byte[] material;

  private DataKey(byte[] material) {
    this.material = material;
  }

  /** A key nobody has held before, made once for a Vault and never again. */
  static DataKey generate(SecureRandom random) {
    byte[] material = new byte[AesGcm.KEY_BYTES];
    random.nextBytes(material);
    return new DataKey(material);
  }

  /** The key as it came back from an unwrap that verified its tag. */
  static DataKey of(byte[] material) {
    if (material.length != AesGcm.KEY_BYTES) {
      throw new IllegalArgumentException(
          "a DataKey is " + AesGcm.KEY_BYTES + " bytes, and this is " + material.length);
    }
    return new DataKey(material.clone());
  }

  /**
   * The key material, for wrapping it or for deriving a secret's own key from it. Package-private,
   * like the type: this is the method that would be the leak if either were public.
   */
  byte[] material() {
    if (isDestroyed()) {
      throw new IllegalStateException("this DataKey has been destroyed");
    }
    return material;
  }

  /** Overwrites the key. Called when a Session ends, and once more where one was borrowed. */
  void destroy() {
    Arrays.fill(material, (byte) 0);
  }

  /**
   * Whether the key has been overwritten, read off the bytes rather than off a flag beside them.
   *
   * <p>A flag and an array can disagree — one is set by {@link #destroy()} and the other is what
   * every operation actually uses — and the bytes are the half that matters. What it costs is that a
   * key which was genuinely thirty-two zero bytes would be misread as destroyed, at a probability of
   * 2⁻²⁵⁶ per Vault ever created.
   */
  private boolean isDestroyed() {
    for (byte each : material) {
      if (each != 0) {
        return false;
      }
    }
    return true;
  }

  /** Redacted whole: a key that printed itself would be a key in every stack trace. */
  @Override
  public String toString() {
    return "DataKey[redacted]";
  }
}
