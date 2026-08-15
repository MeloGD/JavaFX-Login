package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Forgets what an Account has failed, which is how an Administrator releases a colleague who
 * fat-fingered their password. Answered with {@link Ok}, with an {@link ErrorResponse} where the
 * Session is not an Administrator's or where no Account holds that name, and with {@link
 * SessionEnded} where the Session is no longer live.
 *
 * <p>It carries a token rather than a password, as every administrative request does: the
 * Administrator proved who they were when the Session was granted, and the service checks the Role
 * of that Session rather than believing a client about which panel it thinks it is drawing.
 *
 * <p>Nothing here says how long the Lockout had left, or whether there was one at all. Clearing an
 * Account that was not locked is not an error — the Administrator asked for an Account that is not
 * refused, and afterwards it is not refused.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param accountName whose failures are forgotten
 */
public record ClearLockout(SessionToken token, String accountName) implements Request {

  public ClearLockout {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "ClearLockout[redacted]";
  }
}
