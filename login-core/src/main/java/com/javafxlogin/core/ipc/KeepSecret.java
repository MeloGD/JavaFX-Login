package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Puts a named secret into the SecretVault, replacing whatever was kept under that name.
 *
 * <p>An Operator's request and nobody else's, for the reason ADR-0005 gives about reading one: the
 * Administrator holds no Vault access and does not get a way in through the write side either. It is
 * the ProtectedFeature that owns what the Vault holds — this system never learns what a secret is
 * for, only that a Session was allowed to keep it.
 *
 * <p>Answered with {@link Ok}, with an {@link ErrorResponse} where the Session is an Administrator's
 * or holds no wrapped copy of the DataKey, and with {@link SessionEnded} where the Session is no
 * longer live.
 *
 * @param token the Session asking, which must be an Operator's
 * @param name what the secret is called
 * @param secret the secret itself, not retained beyond encrypting it
 */
public record KeepSecret(SessionToken token, String name, char[] secret) implements Request {

  public KeepSecret {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(secret, "secret");
  }

  /** Redacted whole: every field of this is something the Vault exists to keep. */
  @Override
  public String toString() {
    return "KeepSecret[redacted]";
  }
}
