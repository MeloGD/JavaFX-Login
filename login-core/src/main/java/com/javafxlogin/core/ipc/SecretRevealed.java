package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * One secret, decrypted for the request that asked for it.
 *
 * <p>It carries a {@code char[]} rather than a String for the reason every password in this protocol
 * does: what the caller is handed can be overwritten, and nothing here interns a copy that outlives
 * the window it was needed for. What crosses the socket is text either way — ADR-0003 accepts that,
 * as it does for passwords — and what this type buys is that neither end keeps it by accident.
 *
 * <p>It says nothing about the DataKey, because nothing can: the key is a type the vault package does
 * not export, and no response in this protocol has a field for one.
 *
 * @param secret the secret as the ProtectedFeature asked for it
 */
public record SecretRevealed(char[] secret) implements Response {

  public SecretRevealed {
    Objects.requireNonNull(secret, "secret");
  }

  /** Never logged: the secret is the whole of what this carries. */
  @Override
  public String toString() {
    return "SecretRevealed[redacted]";
  }
}
