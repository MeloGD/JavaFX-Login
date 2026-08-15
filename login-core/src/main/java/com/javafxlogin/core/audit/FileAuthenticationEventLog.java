package com.javafxlogin.core.audit;

import com.javafxlogin.core.store.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * The record of AuthenticationEvents as a file beside the CredentialStore, owner-only, one line per
 * event.
 *
 * <p>Beside the store because that is a directory only the account the service runs as can write,
 * which is what stops an Operator deleting the record of what they did.
 *
 * <p>Each event is written and closed rather than buffered, because the service does not run
 * continuously: it stops after its idle period, and an event still sitting in a buffer at that
 * moment would be an event that never happened. Writing is rare enough — this ticket records a
 * clock jump and a configuration change — that the cost of opening the file each time is not worth
 * a mechanism to avoid.
 *
 * <p>Deliberately not yet: the HMAC chain that makes an edit in the middle detectable, the rotation
 * that bounds the file, and the export. All three belong to the audit log's own ticket, which
 * replaces the inside of this class without anything above it changing.
 */
public final class FileAuthenticationEventLog implements AuthenticationEventLog {

  private final Path file;

  public FileAuthenticationEventLog(Path file) {
    this.file = Objects.requireNonNull(file, "file");
  }

  @Override
  public void record(AuthenticationEvent event) {
    Objects.requireNonNull(event, "event");
    try {
      OwnerOnlyFiles.createOrReassert(file);
      Files.writeString(
          file,
          lineFor(event),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      // A disk that is full, or a file that has been made unwritable. Authentication carries on:
      // an audit log that can lock everyone out is a worse failure than one with a gap in it.
    }
  }

  /**
   * One CSV line: the time with its offset, what happened, and who it was about.
   *
   * <p>The timestamp carries a timezone because a bare local time is unreadable a year later on a
   * machine that has since moved.
   */
  private static String lineFor(AuthenticationEvent event) {
    return quoted(
            ZonedDateTime.ofInstant(event.at(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        + ","
        + quoted(event.type().name())
        + ","
        + quoted(event.subject())
        + System.lineSeparator();
  }

  private static String quoted(String field) {
    return '"' + field.replace("\"", "\"\"") + '"';
  }
}
