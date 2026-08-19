package com.javafxlogin.ui.login;

import java.time.Instant;
import java.util.Objects;

/**
 * The one-time secret an Administrator hands over instead of a password, on its way to the screen
 * it is shown on once.
 *
 * <p>It is the only thing this gate ever carries that the service will not say again. The
 * CredentialStore keeps a hash of it and nothing else, and no request reads it back: an
 * Administrator who closes the panel before writing it down has not lost the Account — they ask for
 * another, which is a reset — but they have lost this one.
 *
 * @param secret the secret as a person reads and retypes it, in groups of four characters
 * @param expiresAt when it stops being usable, so that the Administrator can tell the Operator how
 *     long they have
 */
public record EnrolmentSecretIssued(String secret, Instant expiresAt)
    implements AccountProvisioned {

  public EnrolmentSecretIssued {
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** Never logged: the secret is the whole of what this carries. */
  @Override
  public String toString() {
    return "EnrolmentSecretIssued[secret=redacted, expiresAt=" + expiresAt + "]";
  }
}
