package com.javafxlogin.core.vault;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The SecretVault while one Session holds it open, and the only way anything reaches a secret.
 *
 * <p>There is one way to obtain one — {@link SecretVault#unlockFor} — and it takes the password.
 * That is the shape ADR-0004 asked for: a component that cannot be constructed without the key
 * material a password derives cannot be reached by patching a check, because there is no check to
 * patch.
 *
 * <p><b>What it does not have is a method that hands out the DataKey.</b> The key is a type this
 * package does not export, held in a field, and every operation here spends it inside one method
 * call. A secret is decrypted when it is asked for and not before, so the plaintext window is one
 * request rather than the whole Session — which is what makes this worth more than handing the caller
 * a key and trusting them.
 *
 * <p>It is closed when the Session that opened it ends, by whichever of the four things that end a
 * Session got there first, and closing it overwrites the key.
 */
public final class UnlockedVault implements AutoCloseable {

  private final SecretVault vault;
  private final String accountName;
  private final DataKey dataKey;
  private final SecureRandom random;

  UnlockedVault(SecretVault vault, String accountName, DataKey dataKey, SecureRandom random) {
    this.vault = vault;
    this.accountName = accountName;
    this.dataKey = dataKey;
    this.random = random;
  }

  /**
   * The secret kept under that name, or empty where nothing is kept under it.
   *
   * <p>Empty also covers a row this DataKey does not open — a ciphertext moved between names by hand,
   * say, which fails its tag because the key a secret is encrypted under is derived for its name. The
   * caller is not told the two apart, because neither is a question a ProtectedFeature can act on
   * differently.
   */
  public Optional<char[]> secretNamed(String name) {
    Objects.requireNonNull(name, "name");
    byte[] key = keyFor(name);
    try {
      return vault
          .sealedSecretNamed(name)
          .flatMap(sealed -> AesGcm.open(key, sealed.nonce(), sealed.ciphertext()))
          .map(
              plaintext -> {
                try {
                  return Utf8.charsOf(plaintext);
                } finally {
                  Arrays.fill(plaintext, (byte) 0);
                }
              });
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  /** Keeps a secret under that name, replacing whatever was kept under it before. */
  public void keep(String name, char[] secret) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(secret, "secret");
    byte[] key = keyFor(name);
    byte[] plaintext = Utf8.bytesOf(secret);
    try {
      vault.keepSealedSecret(name, AesGcm.seal(key, plaintext, random));
    } finally {
      Arrays.fill(plaintext, (byte) 0);
      Arrays.fill(key, (byte) 0);
    }
  }

  /**
   * Wraps this Session's DataKey under a key derived from a password its holder has just chosen, so
   * that rotating a password costs the password and not the secrets.
   *
   * <p>It goes through the DataKey this Session already holds rather than through the machine's copy,
   * which is the stronger of the two paths available: the key being rewrapped is one that this
   * Operator's own password opened moments ago, so a rewrap cannot quietly hand somebody access they
   * did not already have.
   */
  public void rewrapUnder(char[] newPassword) {
    Objects.requireNonNull(newPassword, "newPassword");
    vault.writeWrapFor(accountName, dataKey, newPassword);
  }

  /** Overwrites the DataKey. After this the Vault is shut and this object refuses everything. */
  @Override
  public void close() {
    dataKey.destroy();
  }

  /**
   * The key one secret is encrypted under: derived from the DataKey for that name and nothing else.
   *
   * <p>Deriving per name rather than using the DataKey directly is ADR-0004's wording, and it earns
   * its keep twice: no ciphertext in the file is under the DataKey itself, and a row carried from one
   * name to another fails its tag instead of decrypting into the wrong answer.
   */
  private byte[] keyFor(String name) {
    return Hkdf.expand(dataKey.material(), name, AesGcm.KEY_BYTES);
  }
}
