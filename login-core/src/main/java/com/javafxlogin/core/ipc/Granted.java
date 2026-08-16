package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Authentication succeeded, in the Role the {@link Authenticate} asked for.
 *
 * <p>It carries no Role of its own. The client already knows which one it asked to act in, and a
 * Role in the answer would invite it to route on what came back rather than on what it requested —
 * which is the decision the service is here to make.
 *
 * @param token the opaque 128-bit SessionToken, never persisted and never logged
 * @param passwordResetAt when an Administrator took this Account's password away, where that has
 *     happened since the last time anybody logged in and the Operator has not been told yet. It
 *     rides on the admission rather than on the refusal before it, because the person it is for is
 *     the one who has just proved they hold the Account — and it is said once: the service forgets
 *     it as it says it, so a reset is news the next morning and not a banner for a fortnight.
 */
public record Granted(SessionToken token, Optional<Instant> passwordResetAt) implements Response {

  public Granted {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(passwordResetAt, "passwordResetAt");
  }

  /** An ordinary admission, with nothing the Operator is owed being told. */
  public Granted(SessionToken token) {
    this(token, Optional.empty());
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "Granted[token=redacted, passwordResetAt=" + passwordResetAt + "]";
  }
}
