package com.javafxlogin.core.ipc;

import com.javafxlogin.core.audit.AuthenticationEventExport;
import java.util.Objects;

/**
 * The record was copied to the file the Administrator named.
 *
 * <p>It carries the export's own summary rather than a copy of its two numbers, the way {@link
 * Assessed} carries an {@code Assessment}: what an export came to is the audit package's word, and
 * repeating it here would be a second place for it to drift.
 *
 * <p>Not the entries. Not one of them. An Administrator who wants to read what happened opens the
 * file; what crosses this channel is how much there was of it and whether it still hangs together.
 *
 * @param export how many entries were copied, and whether the chain held
 */
public record AuthenticationEventsExported(AuthenticationEventExport export) implements Response {

  public AuthenticationEventsExported {
    Objects.requireNonNull(export, "export");
  }
}
