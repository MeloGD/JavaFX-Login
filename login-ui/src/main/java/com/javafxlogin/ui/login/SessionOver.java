package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.SessionEndedReason;
import java.util.Objects;

/**
 * The Session is over, and this is what the service said about why.
 *
 * <p>The reason is worded by {@link SessionEndedText} rather than here, for the same reason a
 * PolicyViolation is: the privileged process names things and the window that draws them owns the
 * sentence and its translation.
 */
public record SessionOver(SessionEndedReason reason) implements SessionStatus {

  public SessionOver {
    Objects.requireNonNull(reason, "reason");
  }
}
