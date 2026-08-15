package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Sets how long a Session may idle for this deployment, or switches expiry off for a kiosk.
 * Answered with {@link Ok}, with an {@link ErrorResponse} where the Session is not an
 * Administrator's, or with {@link SessionEnded} where it is no longer live.
 *
 * <p>It carries a token rather than a password: the Administrator proved who they were when the
 * Session was granted, and the service checks the Role of that Session rather than believing a
 * client about which panel it thinks it is drawing.
 *
 * @param token the Session making the change, which must be an Administrator's
 * @param period what a Session may idle for from now on
 */
public record ChangeInactivityPeriod(SessionToken token, InactivityPeriod period)
    implements Request {

  public ChangeInactivityPeriod {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(period, "period");
  }

  /** Redacts the token; the period is configuration and is not a secret. */
  @Override
  public String toString() {
    return "ChangeInactivityPeriod[token=redacted, period=" + period.text() + "]";
  }
}
