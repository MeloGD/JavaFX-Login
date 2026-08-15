package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionEndedReason;
import java.util.Objects;

/**
 * The Session the client named is over, and this is why.
 *
 * <p>Whoever receives it held a token this service issued, so the reason costs nothing to give and
 * buys a person being told why the window in front of them closed.
 */
public record SessionEnded(SessionEndedReason reason) implements Response {

  public SessionEnded {
    Objects.requireNonNull(reason, "reason");
  }
}
