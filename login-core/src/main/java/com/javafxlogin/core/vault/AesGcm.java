package com.javafxlogin.core.vault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256 in Galois/Counter Mode: the one thing in this package that turns bytes into other bytes.
 *
 * <p>It is authenticated encryption, which is what lets this Vault tell a wrong password from a
 * right one without comparing anything to anything. A key derived from the wrong password fails the
 * tag, and a failed tag comes back here as {@link Optional#empty()} rather than as an exception —
 * the caller has one answer to give either way, and a wrong password is not an error.
 *
 * <p>Every nonce is fresh from a {@link SecureRandom}. Twelve bytes is what GCM is specified for and
 * what its counter is built around; the number of wraps and secrets in an offline deployment's Vault
 * is nowhere near where random nonces of that width start to worry anybody.
 */
final class AesGcm {

  /** A 256-bit key, which is also what a {@link DataKey} and a derived KEK are. */
  static final int KEY_BYTES = 32;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String ALGORITHM = "AES";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private AesGcm() {}

  /**
   * A ciphertext and the nonce it was produced under, which is what the file holds.
   *
   * @param nonce fresh for this and no other encryption
   * @param ciphertext with GCM's tag on the end of it, as the JDK writes it
   */
  record Sealed(byte[] nonce, byte[] ciphertext) {}

  /** Encrypts under a fresh nonce. */
  static Sealed seal(byte[] key, byte[] plaintext, SecureRandom random) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = cipherFor(Cipher.ENCRYPT_MODE, key, nonce);
      return new Sealed(nonce, cipher.doFinal(plaintext));
    } catch (GeneralSecurityException e) {
      throw unavailable(e);
    }
  }

  /**
   * Decrypts, or answers that this key does not open this ciphertext.
   *
   * <p>The empty answer is the interesting one: it is a wrong password at the moment a Vault is
   * unlocked, and it is a file somebody edited by hand at every other moment. Both are the same
   * answer here, because both mean the same thing — these bytes are not what they claim to be.
   */
  static Optional<byte[]> open(byte[] key, byte[] nonce, byte[] ciphertext) {
    try {
      Cipher cipher = cipherFor(Cipher.DECRYPT_MODE, key, nonce);
      return Optional.of(cipher.doFinal(ciphertext));
    } catch (AEADBadTagException e) {
      return Optional.empty();
    } catch (GeneralSecurityException e) {
      throw unavailable(e);
    }
  }

  private static Cipher cipherFor(int mode, byte[] key, byte[] nonce)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(mode, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, nonce));
    return cipher;
  }

  /**
   * Every Java runtime this product can start on has AES-256-GCM, and every key reaching here is
   * thirty-two bytes this package produced. A runtime without it is not a machine this service can
   * protect anything on.
   */
  private static IllegalStateException unavailable(GeneralSecurityException cause) {
    return new IllegalStateException("this runtime cannot compute " + TRANSFORMATION, cause);
  }
}
