package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * The SessionGuard saw the Operator do something, and says so. Answered with {@link SessionLive} or
 * {@link SessionEnded}.
 *
 * <p>It reports and asks for nothing. Whether the Session survived the gap since the last report is
 * the AuthenticationService's decision, made against both of its clocks when this arrives — which
 * is why a client that stops reporting simply expires, and why a patched one cannot hold a Session
 * open by insisting that it should be.
 *
 * @param token the Session the activity happened in
 */
public record ReportActivity(SessionToken token) implements Request {

  public ReportActivity {
    Objects.requireNonNull(token, "token");
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "ReportActivity[token=redacted]";
  }
}
