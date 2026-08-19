package com.javafxlogin.core.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF's expand step, RFC 5869, which is how a secret's own key comes out of the DataKey.
 *
 * <p>Expand and not extract. Extraction exists to even out key material that is not uniformly
 * random — a Diffie-Hellman output, a password — and the DataKey is thirty-two bytes straight
 * from a {@link java.security.SecureRandom}. Running it through extraction first would be ceremony that
 * buys nothing; using it as the pseudorandom key directly is what RFC 5869 §3.3 describes.
 *
 * <p>The name of the secret is the {@code info} string, so every secret in the Vault is encrypted
 * under a different key and a ciphertext carried from one row to another fails its tag rather than
 * decrypting into somebody else's secret.
 */
final class Hkdf {

  private static final String ALGORITHM = "HmacSHA256";

  private Hkdf() {}

  /**
   * Derives {@code length} bytes from a uniformly random key, bound to {@code info}.
   *
   * @param key the pseudorandom key, which for this Vault is always the DataKey
   * @param info what the derived key is for, which is always the name of one secret
   */
  static byte[] expand(byte[] key, String info, int length) {
    byte[] context = info.getBytes(StandardCharsets.UTF_8);
    byte[] derived = new byte[length];
    byte[] block = new byte[0];
    int written = 0;
    for (int counter = 1; written < length; counter++) {
      block = mac(key, block, context, (byte) counter);
      int take = Math.min(block.length, length - written);
      System.arraycopy(block, 0, derived, written, take);
      written += take;
    }
    return derived;
  }

  private static byte[] mac(byte[] key, byte[] previousBlock, byte[] info, byte counter) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      mac.update(previousBlock);
      mac.update(info);
      mac.update(counter);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      // HMAC-SHA-256 with a 32-byte key, on a runtime that has already done AES-GCM to get here.
      throw new IllegalStateException("this runtime cannot compute " + ALGORITHM, e);
    }
  }
}
