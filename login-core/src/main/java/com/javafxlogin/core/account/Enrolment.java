package com.javafxlogin.core.account;

import java.time.Instant;
import java.util.Objects;

/**
 * What the CredentialStore remembers about an Account that is waiting for somebody to give it a
 * password: the hash of the secret that was issued, and when it was issued.
 *
 * <p>Not the secret. It was shown once, to the Administrator who asked for it, and this is what is
 * left behind — enough to recognise it when it comes back and not enough to say it again.
 *
 * <p>When it stops being usable is not here. That is configuration, held beside the Accounts and
 * read again on every decision, so this record holds the fact and the policy stays where a
 * deployment can change it.
 *
 * @param secretHash the SHA-256 of the EnrolmentSecret, as hexadecimal
 * @param issuedAt when it was handed over
 */
public record Enrolment(String secretHash, Instant issuedAt) {

  public Enrolment {
    Objects.requireNonNull(secretHash, "secretHash");
    Objects.requireNonNull(issuedAt, "issuedAt");
  }

  /** Redacts the hash, as an Account redacts its own: this may be printed, its material may not. */
  @Override
  public String toString() {
    return "Enrolment[secretHash=redacted, issuedAt=" + issuedAt + "]";
  }
}
