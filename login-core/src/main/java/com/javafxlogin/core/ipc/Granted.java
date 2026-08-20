package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.time.Instant;
import java.util.Locale;
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
 * @param passwordResetAt when an Administrator took this Account's password away, where the person
 *     holding the Account has not said they have read about it yet. It rides on the admission
 *     rather than on the refusal before it, because the person it is for is the one who has just
 *     proved they hold the Account — and it is said on every admission until an {@link
 *     AcknowledgePasswordReset} arrives, because a notice that was sent is not a notice that
 *     arrived: a client that died before drawing a window would otherwise have spent the only copy.
 * @param languagePreference the language this Account's holder reads the interface in, or empty
 *     where they have said nothing and the machine the client runs on answers for them. It rides
 *     on the admission because this is the first moment the service knows whose preference to
 *     apply: before it there is a name somebody typed, and no Account it has been proved to be.
 */
public record Granted(
    SessionToken token, Optional<Instant> passwordResetAt, Optional<Locale> languagePreference)
    implements Response {

  public Granted {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(passwordResetAt, "passwordResetAt");
    Objects.requireNonNull(languagePreference, "languagePreference");
  }

  /** An ordinary admission, with nothing the Operator is owed being told. */
  public Granted(SessionToken token) {
    this(token, Optional.empty(), Optional.empty());
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "Granted[token=redacted, passwordResetAt="
        + passwordResetAt
        + ", languagePreference="
        + languagePreference
        + "]";
  }
}
