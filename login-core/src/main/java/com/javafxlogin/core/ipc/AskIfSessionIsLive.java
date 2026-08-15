package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Asks whether a Session is still live, and how long it has left. Answered with {@link SessionLive}
 * or {@link SessionEnded}.
 *
 * <p>Asking is not activity: this leaves the countdown exactly where it was, which is what lets the
 * SessionGuard find out that the Session it is watching has run out without keeping it alive by
 * looking at it.
 *
 * @param token the Session being asked about
 */
public record AskIfSessionIsLive(SessionToken token) implements Request {

  public AskIfSessionIsLive {
    Objects.requireNonNull(token, "token");
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "AskIfSessionIsLive[token=redacted]";
  }
}
