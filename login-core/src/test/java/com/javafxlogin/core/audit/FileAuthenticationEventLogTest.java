package com.javafxlogin.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file the AuthenticationEvents are written to: chained, flushed, bounded, and exported.
 *
 * <p>Stories 76 to 81. These are the properties of the record itself rather than of what gets
 * recorded — which events the service writes is {@code AuthenticationEventRecordingTest}'s
 * business.
 */
class FileAuthenticationEventLogTest {

  private static final Instant WHEN = Instant.parse("2026-03-01T09:00:00Z");

  @TempDir Path directory;

  private Path log() {
    return directory.resolve("authentication-events.csv");
  }

  private Path key() {
    return directory.resolve("authentication-events.key");
  }

  private FileAuthenticationEventLog openLog() {
    return new FileAuthenticationEventLog(log(), key());
  }

  /** Story 79: the service stops when it is idle, so an entry in a buffer is an entry lost. */
  @Test
  void anEntryIsOnTheDiskBeforeRecordingReturns() throws IOException {
    FileAuthenticationEventLog log = openLog();

    log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, "finch.mercer"));

    assertTrue(
        Files.readString(log()).contains("finch.mercer"),
        "nothing was on the disk when recording returned");
  }

  /** Story 76: unambiguous a year later, on a machine that has since moved. */
  @Test
  void theTimestampCarriesATimezone() throws IOException {
    openLog().record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, "finch.mercer"));

    String recorded = Files.readString(log());
    assertTrue(
        recorded.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T[\\d:.]+(Z|[+-]\\d{2}:\\d{2}).*"),
        () -> "no offset in the timestamp: " + recorded);
  }

  /** One event is one line, whatever an Account is called. */
  @Test
  void anAccountNameCannotSplitAnEntryInTwo() throws IOException {
    openLog().record(event(AuthenticationEventType.ACCOUNT_LOCKED_OUT, "finch\nmercer"));

    assertEquals(1, Files.readAllLines(log()).size());
  }

  /** Story 78, and the reason the whole thing exists: nothing was touched, so nothing shows. */
  @Test
  void anUntouchedRecordExportsWithItsChainIntact() throws IOException {
    FileAuthenticationEventLog log = openLog();
    recordThree(log);

    AuthenticationEventExport export = log.exportTo(directory.resolve("export.csv"));

    assertEquals(3, export.events());
    assertTrue(export.chainIntact(), "an untouched record reported itself edited");
  }

  @Test
  void anEditedEntryIsFoundAtTheNextExport() throws IOException {
    FileAuthenticationEventLog log = openLog();
    recordThree(log);

    rewriteLine(1, line -> line.replace("second", "somebody.else"));

    assertFalse(log.exportTo(directory.resolve("export.csv")).chainIntact());
  }

  @Test
  void anEntryRemovedFromTheMiddleIsFoundAtTheNextExport() throws IOException {
    FileAuthenticationEventLog log = openLog();
    recordThree(log);

    List<String> kept = new ArrayList<>(Files.readAllLines(log()));
    kept.remove(1);
    Files.write(log(), kept, StandardCharsets.UTF_8);

    assertFalse(log.exportTo(directory.resolve("export.csv")).chainIntact());
  }

  /**
   * The chain outlives the process that wrote it. A service that started a new chain on every start
   * would break it five idle minutes after every login, which reads exactly like an edited record.
   */
  @Test
  void theChainCarriesOnAcrossARestartOfTheService() throws IOException {
    recordThree(openLog());

    FileAuthenticationEventLog afterTheRestart = openLog();
    afterTheRestart.record(event(AuthenticationEventType.LOCKOUT_CLEARED, "fourth"));

    assertTrue(afterTheRestart.exportTo(directory.resolve("export.csv")).chainIntact());
  }

  /** Story 80: the record is bounded, in the size of a file and in the number of them. */
  @Test
  void theRecordRotatesAndCannotGrowWithoutBound() throws IOException {
    FileAuthenticationEventLog log = openLog();

    // An absurd subject, so that the shipped megabyte is reached in a handful of entries rather
    // than in ten thousand. What is being asserted is the bound, not the size of a name.
    String enormous = "e".repeat(200_000);
    for (int entry = 0; entry < 40; entry++) {
      log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, enormous));
    }

    List<Path> kept = eventFiles();
    long total = totalSize();
    assertEquals(5, kept.size(), () -> "kept " + kept);
    assertTrue(total < 6L * (1 << 20), () -> "the record grew to " + total + " bytes");
  }

  /** Removing a whole file is as visible as removing a line, unless it is the oldest one kept. */
  @Test
  void aWholeFileRemovedFromTheMiddleIsFoundAtTheNextExport() throws IOException {
    FileAuthenticationEventLog log = openLog();
    String enormous = "e".repeat(200_000);
    for (int entry = 0; entry < 15; entry++) {
      log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, enormous));
    }

    Files.delete(directory.resolve("authentication-events.csv.1"));

    assertFalse(log.exportTo(directory.resolve("export.csv")).chainIntact());
  }

  /** Story 75: every entry still kept, oldest first, in one file. */
  @Test
  void theExportHoldsEveryEntryOldestFirst() throws IOException {
    FileAuthenticationEventLog log = openLog();
    recordThree(log);
    Path export = directory.resolve("export.csv");

    log.exportTo(export);

    List<String> lines = Files.readAllLines(export);
    assertEquals(4, lines.size(), () -> "a header and three entries, and got " + lines);
    assertTrue(lines.get(0).contains("chain"), "the export names its columns");
    assertTrue(lines.get(1).contains("first"), () -> "out of order: " + lines);
    assertTrue(lines.get(3).contains("third"), () -> "out of order: " + lines);
  }

  /** A privileged process asked to write where something already is does not write. */
  @Test
  void anExportNeverOverwritesWhatIsAlreadyThere() throws IOException {
    FileAuthenticationEventLog log = openLog();
    recordThree(log);
    Path export = Files.writeString(directory.resolve("export.csv"), "something else");

    assertThrows(FileAlreadyExistsException.class, () -> log.exportTo(export));
    assertEquals("something else", Files.readString(export));
  }

  /** Story 81: a full disk must not lock everyone out, so recording swallows what it cannot do. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void recordingSwallowsAFileItCannotWrite() throws IOException {
    Path unwritable = Files.createDirectory(directory.resolve("unwritable"));
    FileAuthenticationEventLog log =
        new FileAuthenticationEventLog(
            unwritable.resolve("authentication-events.csv"), unwritable.resolve("events.key"));
    Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("r-x------"));

    try {
      log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, "finch.mercer"));
    } finally {
      Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("rwx------"));
    }

    // Swallowed, rather than written somewhere else: an event that could not be recorded is a gap
    // in the record, and the caller is never told which of the two happened.
    assertFalse(Files.exists(unwritable.resolve("authentication-events.csv")));
  }

  /** ADR-0002: everything the privileged process writes, including what it exports. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void everyFileTheRecordIsMadeOfIsOwnerOnly() throws IOException {
    FileAuthenticationEventLog log = openLog();
    String enormous = "e".repeat(200_000);
    for (int entry = 0; entry < 15; entry++) {
      log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, enormous));
    }
    log.exportTo(directory.resolve("export.csv"));

    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
            () -> file + " is readable by more than the account the service runs as");
      }
    }
  }

  // --- getting there -------------------------------------------------------------------------

  private void recordThree(FileAuthenticationEventLog log) {
    log.record(event(AuthenticationEventType.ADMINISTRATOR_CREATED, "first"));
    log.record(event(AuthenticationEventType.AUTHENTICATION_SUCCEEDED, "second"));
    log.record(event(AuthenticationEventType.ACCOUNT_LOCKED_OUT, "third"));
  }

  private static AuthenticationEvent event(AuthenticationEventType type, String subject) {
    return new AuthenticationEvent(WHEN.plus(Duration.ofMinutes(1)), type, subject);
  }

  private void rewriteLine(int index, java.util.function.UnaryOperator<String> edit)
      throws IOException {
    List<String> lines = new ArrayList<>(Files.readAllLines(log()));
    lines.set(index, edit.apply(lines.get(index)));
    Files.write(log(), lines, StandardCharsets.UTF_8);
  }

  private List<Path> eventFiles() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(file -> file.getFileName().toString().startsWith("authentication-events.csv"))
          .sorted()
          .toList();
    }
  }

  private long totalSize() throws IOException {
    long total = 0;
    for (Path file : eventFiles()) {
      total += Files.size(file);
    }
    return total;
  }
}
