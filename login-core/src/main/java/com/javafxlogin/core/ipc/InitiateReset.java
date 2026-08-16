package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Takes an Account's password away and issues a one-time secret in its place, which is how an
 * Operator who has forgotten theirs gets back in — and how an Administrator re-issues a secret that
 * was lost or that ran out.
 *
 * <p>The two are one request because they are one thing: the Account ends up awaiting enrolment with
 * a secret nobody but the person holding it can use. Whether there was a password to take away is
 * the only difference, and it decides one thing — whether the Operator is told at their next login
 * that their password was reset, and when.
 *
 * <p>The old password stops working the moment this is answered, before the secret has been handed
 * over or used. That is the point of it: a reset that could be started and quietly abandoned would
 * leave an Administrator having decided something about an Account nobody would ever notice.
 *
 * <p>Answered with an {@link EnrolmentIssued}, or with an {@link ErrorResponse} where the Session is
 * not an Administrator's, where no Account holds the name, or where the name is the Administrator's
 * own — that password is self-chosen at the first run and there is nobody to hand a secret to.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param accountName whose password is taken away
 */
public record InitiateReset(SessionToken token, String accountName) implements Request {

  public InitiateReset {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "InitiateReset[redacted]";
  }
}
