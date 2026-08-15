package com.javafxlogin.core.authentication;

import com.javafxlogin.core.session.SessionEndedReason;
import java.util.Objects;

/**
 * A Session the clocks have just ended, and why.
 *
 * <p>It exists so that {@link Sessions} can say what happened without knowing that anything is
 * recorded: the registry answers, and whether an AuthenticationEvent follows is the service's
 * decision.
 *
 * @param accountName whose Session it was, which is what the audit log records it against
 * @param reason what the clocks said
 */
public record ExpiredSession(String accountName, SessionEndedReason reason) {

  public ExpiredSession {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(reason, "reason");
  }
}
