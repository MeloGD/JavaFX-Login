package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Ends a Session deliberately. Answered with {@link Ok}, or with {@link SessionEnded} where there
 * was nothing left to end.
 *
 * <p>The connection is not closed by this, and closing it would not replace it: a person logging
 * out is handed back to the login screen, and the screen they are handed back to needs the same
 * connection to make its next attempt over.
 *
 * @param token the Session to end
 */
public record Logout(SessionToken token) implements Request {

  public Logout {
    Objects.requireNonNull(token, "token");
  }

  /** Never logged: the token is the whole of what this carries. */
  @Override
  public String toString() {
    return "Logout[token=redacted]";
  }
}
