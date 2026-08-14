package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Authentication succeeded, in the Role the {@link Authenticate} asked for.
 *
 * <p>It carries no Role of its own. The client already knows which one it asked to act in, and a
 * Role in the answer would invite it to route on what came back rather than on what it requested —
 * which is the decision the service is here to make.
 *
 * @param token the opaque 128-bit SessionToken, never persisted and never logged
 */
public record Granted(SessionToken token) implements Response {

  public Granted {
    Objects.requireNonNull(token, "token");
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "Granted[token=redacted]";
  }
}
