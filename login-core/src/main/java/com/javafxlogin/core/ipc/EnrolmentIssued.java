package com.javafxlogin.core.ipc;

import java.time.Instant;
import java.util.Objects;

/**
 * A one-time enrolment secret, on its way to the screen it is shown on once.
 *
 * <p>This is the only message in this protocol that carries something the service will never say
 * again. The CredentialStore keeps a hash of it and nothing else, so an Administrator who closes the
 * window before writing it down has not lost the Account — they ask for another with an {@link
 * InitiateReset} — but they have lost this one, and no request will read it back.
 *
 * <p>It is never written to the audit log. What is recorded there is that an enrolment was issued
 * and against which Account, which is the fact somebody reviewing the record needs; the secret
 * itself in a file that outlives the moment would undo the whole of the arrangement.
 *
 * @param secret the secret as a person reads and retypes it, in groups of four characters
 * @param expiresAt when it stops being usable, computed from what the deployment has configured and
 *     said here so that the Administrator can tell the Operator how long they have
 */
public record EnrolmentIssued(String secret, Instant expiresAt) implements Response {

  public EnrolmentIssued {
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** Never logged: the secret is the whole of what this carries. */
  @Override
  public String toString() {
    return "EnrolmentIssued[secret=redacted, expiresAt=" + expiresAt + "]";
  }
}
