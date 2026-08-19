package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Asks the SecretVault for one named secret, which is what a ProtectedFeature does when it needs a
 * credential for a system it connects to.
 *
 * <p>One secret at a time, by name, and never "all of them": what the service decrypts is what was
 * asked for, at the moment it was asked for, so the plaintext window is this request rather than the
 * whole Session. There is no request that asks for the DataKey, and there is no request that lists
 * what the Vault holds.
 *
 * <p>Answered with {@link SecretRevealed}; with an {@link ErrorResponse} where nothing is kept under
 * that name, where the Session is an Administrator's, or where this Account holds no wrapped copy of
 * the DataKey; and with {@link SessionEnded} where the Session is no longer live.
 *
 * @param token the Session asking, which must be an Operator's
 * @param name what the secret is called, as the ProtectedFeature knows it
 */
public record ReadSecret(SessionToken token, String name) implements Request {

  public ReadSecret {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(name, "name");
  }

  /** Redacted whole: the name of a secret says which systems this deployment talks to. */
  @Override
  public String toString() {
    return "ReadSecret[redacted]";
  }
}
