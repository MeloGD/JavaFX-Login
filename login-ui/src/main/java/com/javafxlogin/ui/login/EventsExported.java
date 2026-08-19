package com.javafxlogin.ui.login;

import com.javafxlogin.core.audit.AuthenticationEventExport;
import java.util.Objects;

/**
 * The record was copied, and this is what the copy came to.
 *
 * <p>Two numbers and no events, as the service answers it. The one worth putting in front of a
 * person is the second: an export whose chain did not hold is saying the record was edited or had
 * entries removed since it was written, which is the only thing this system can ever say about
 * that.
 *
 * @param export how many entries were copied, and whether the EventChain still held
 */
public record EventsExported(AuthenticationEventExport export) implements ExportOutcome {

  public EventsExported {
    Objects.requireNonNull(export, "export");
  }
}
