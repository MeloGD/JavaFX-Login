package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * Turns a one-time enrolment secret into a password the person using the Account chose themselves.
 *
 * <p>It carries no {@link com.javafxlogin.core.session.SessionToken}, and that is not an oversight:
 * whoever sends this has not authenticated and cannot, because the Account they are enrolling has no
 * password yet. What stands in for a Session is the secret, which the service issued, hashed and
 * expires.
 *
 * <p>Answered with {@link Ok}, with a {@link PolicyRefused} where the chosen password breaks a rule,
 * and with a {@link Denied} where the secret is wrong, has expired, has been used already, or names
 * an Account that is not awaiting enrolment. Those four are one answer on purpose — the same
 * refusal, in the same words, as a wrong password at the login screen — because telling them apart
 * would say which names are Accounts and which of those are waiting to be claimed.
 *
 * @param accountName the Account being enrolled
 * @param secret the one-time secret an Administrator handed over, as it was typed
 * @param password the password chosen here and known to nobody else, not retained beyond hashing it
 */
public record CompleteEnrolment(String accountName, char[] secret, char[] password)
    implements Request {

  public CompleteEnrolment {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(password, "password");
  }

  /** Redacted whole: every field of this is something the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "CompleteEnrolment[redacted]";
  }
}
