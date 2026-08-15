package com.javafxlogin.core.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * A recorded fact about access, written by the privileged process and never read back by the
 * application.
 *
 * @param at when it happened, by the machine's clock. An event about that clock having moved
 *     carries a timestamp from the clock that moved, which is the honest record: the service knows
 *     the two clocks disagreed and does not know which of them is right.
 * @param type what happened, named rather than worded — whoever reads the exported log words it
 * @param subject the Account the event is about, or a fixed placeholder where there is no Account
 *     to name. Never a value someone typed.
 */
public record AuthenticationEvent(Instant at, AuthenticationEventType type, String subject) {

  public AuthenticationEvent {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(subject, "subject");
  }
}
