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

  /**
   * What stands in the subject where there is no Account to name: an attempt against a name nobody
   * holds, or one refused before any name was looked up.
   *
   * <p>It is written the way it is so that it cannot be mistaken for a name, and no Account can be
   * called it — the bundled blocklist refuses it, which is what stops an Operator being provisioned
   * under the placeholder and hiding among the events that carry it.
   */
  public static final String NO_ACCOUNT = "(no account)";

  public AuthenticationEvent {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(subject, "subject");
  }
}
