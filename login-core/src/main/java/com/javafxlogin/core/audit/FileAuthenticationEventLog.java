package com.javafxlogin.core.audit;

import com.javafxlogin.core.store.OwnerOnlyFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The record of AuthenticationEvents as files beside the CredentialStore, owner-only, one line per
 * event.
 *
 * <p>Beside the store because that is a directory only the account the service runs as can write,
 * which is what stops an Operator deleting the record of what they did.
 *
 * <p>Each event is written and forced to the disk rather than buffered, because the service does
 * not run continuously: it stops after its idle period, and an event still sitting in a buffer at
 * that moment would be an event that never happened. Writing is rare enough — a person logs in a
 * handful of times a day — that the cost of opening and syncing the file each time is not worth a
 * mechanism to avoid, and it is the same bargain {@code synchronous = FULL} makes in the store.
 *
 * <h2>The shape of a line</h2>
 *
 * <p>Four CSV fields: the chain value, the time with its offset, what happened, and who it was
 * about. The chain value comes <em>first</em>, which is the one surprising thing here and is
 * deliberate: it means finding where it ends never depends on parsing a name somebody else chose.
 * An Account name is the only field a person controls, and it is written last so that nothing after
 * it has to be found by counting quotes through it.
 *
 * <p>For the same reason a subject is written on one line whatever it contains. One event is one
 * line; an Account name carrying a newline would otherwise be an Account name that decides how many
 * entries there are.
 *
 * <h2>What is bounded and what is not</h2>
 *
 * <p>The current file is rotated once the next entry would take it past {@link #LARGEST_FILE}, and
 * {@link #FILES_KEPT} files are kept in all, so the record cannot fill a disk (story 80). The chain
 * runs across the rotation, so removing a whole file is as visible as removing a line — up to the
 * oldest one still kept, whose predecessor is gone by design.
 *
 * <p>Nothing here fails an operation by failing to record it. A full disk, an unwritable file or a
 * key that cannot be read all end the same way: the event is lost and authentication carries on.
 */
public final class FileAuthenticationEventLog
    implements AuthenticationEventLog, AuthenticationEventArchive {

  /**
   * How large one file may become. A megabyte is some eight thousand entries — months of a
   * single-machine deployment, and small enough to open in anything.
   */
  private static final long LARGEST_FILE = 1L << 20;

  /** How many files are kept in all: the one being written and four behind it. */
  private static final int FILES_KEPT = 5;

  /** Named for whoever opens the export, since the entries themselves carry no header. */
  private static final String HEADER = "\"chain\",\"at\",\"event\",\"subject\"";

  private final Path file;
  private final Path keyFile;

  private EventChain chain;

  /**
   * The chain value the next entry follows: null until it has been looked for on the disk, and the
   * empty string once it has been and there was nothing there to follow.
   */
  private String chainValueOfTheLastEntry;

  /**
   * @param file the current file, whose rotated predecessors are named after it
   * @param keyFile where the key the chain is computed under lives, made on the first event
   */
  public FileAuthenticationEventLog(Path file, Path keyFile) {
    this.file = Objects.requireNonNull(file, "file");
    this.keyFile = Objects.requireNonNull(keyFile, "keyFile");
  }

  @Override
  public void record(AuthenticationEvent event) {
    Objects.requireNonNull(event, "event");
    try {
      String entry = entryFor(event);
      String value = chain().after(chainValueOfTheLastEntry(), entry);
      String line = quoted(value) + "," + entry + System.lineSeparator();

      rotateIfTheFileWouldOverflow(line.getBytes(StandardCharsets.UTF_8).length);
      OwnerOnlyFiles.createOrReassert(file);
      append(line);

      // Only once it is on the disk. An entry that was not written is one the next entry must not
      // chain onto, or a failed write would read afterwards as an edited record rather than as the
      // gap it is.
      chainValueOfTheLastEntry = value;
    } catch (IOException e) {
      // A disk that is full, a file that has been made unwritable, a key that cannot be read.
      // Authentication carries on: an audit log that can lock everyone out is a worse failure
      // than one with a gap in it (story 81).
    }
  }

  @Override
  public AuthenticationEventExport exportTo(Path destination) throws IOException {
    Objects.requireNonNull(destination, "destination");
    OwnerOnlyFiles.createNew(destination);
    try (BufferedWriter out =
        Files.newBufferedWriter(destination, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
      out.write(HEADER);
      out.newLine();
      return copyInto(out);
    } catch (IOException e) {
      // Half an export is worse than none: whoever reads it would be reading a record that stops
      // for a reason it does not state.
      Files.deleteIfExists(destination);
      throw e;
    }
  }

  /** Copies every entry still kept, oldest first, checking that each follows from the last. */
  private AuthenticationEventExport copyInto(BufferedWriter out) throws IOException {
    long events = 0;
    boolean intact = true;
    String followed = "";
    boolean nothingCheckedYet = true;

    for (Path source : oldestFirst()) {
      for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        String value = chainValueOf(line);
        // The oldest entry kept is taken on trust: what it followed was rotated away, so there is
        // nothing left to check it against. Every entry after it is checked against the one before.
        if (!nothingCheckedYet && !chain().after(followed, entryIn(line)).equals(value)) {
          intact = false;
        }
        nothingCheckedYet = false;
        followed = value;
        events++;
        out.write(line);
        out.newLine();
      }
    }
    return new AuthenticationEventExport(events, intact);
  }

  /**
   * The chain value the next entry follows, found on the disk the first time it is asked for.
   *
   * <p>Reading it is not reading the record back: what is read is one field of one line, never an
   * event, and it goes nowhere but into the next HMAC. It has to come from the disk because the
   * service stops when it is idle, and a chain that started again on every start would break at
   * every restart — which is what a tampered record looks like.
   */
  private String chainValueOfTheLastEntry() throws IOException {
    if (chainValueOfTheLastEntry == null) {
      chainValueOfTheLastEntry = lastChainValueOnDisk();
    }
    return chainValueOfTheLastEntry;
  }

  /** The last entry of the newest file that has one, which is the newest entry there is. */
  private String lastChainValueOnDisk() throws IOException {
    List<Path> kept = oldestFirst();
    for (int generation = kept.size() - 1; generation >= 0; generation--) {
      List<String> lines = Files.readAllLines(kept.get(generation), StandardCharsets.UTF_8);
      for (int line = lines.size() - 1; line >= 0; line--) {
        if (!lines.get(line).isBlank()) {
          return chainValueOf(lines.get(line));
        }
      }
    }
    return "";
  }

  /**
   * Starts a new file where the next entry would take the current one past its bound, keeping
   * {@link #FILES_KEPT} in all and dropping the oldest.
   *
   * <p>An entry larger than the whole bound is written anyway rather than rotating for ever: a file
   * with nothing in it is never rotated.
   */
  private void rotateIfTheFileWouldOverflow(int lineLength) throws IOException {
    if (!Files.exists(file)) {
      return;
    }
    long size = Files.size(file);
    if (size == 0 || size + lineLength <= LARGEST_FILE) {
      return;
    }
    Files.deleteIfExists(rotated(FILES_KEPT - 1));
    for (int generation = FILES_KEPT - 2; generation >= 1; generation--) {
      if (Files.exists(rotated(generation))) {
        Files.move(
            rotated(generation), rotated(generation + 1), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.move(file, rotated(1), StandardCopyOption.REPLACE_EXISTING);
  }

  /** The files still kept, oldest first, ending with the one being written. */
  private List<Path> oldestFirst() {
    List<Path> files = new ArrayList<>(FILES_KEPT);
    for (int generation = FILES_KEPT - 1; generation >= 1; generation--) {
      if (Files.exists(rotated(generation))) {
        files.add(rotated(generation));
      }
    }
    if (Files.exists(file)) {
      files.add(file);
    }
    return files;
  }

  /** {@code authentication-events.csv.1} is the file rotated most recently. */
  private Path rotated(int generation) {
    return file.resolveSibling(file.getFileName() + "." + generation);
  }

  private void append(String line) throws IOException {
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
      // Story 79. Closing the file would only hand the bytes to the operating system, which is
      // enough for a service that stops and not for a machine that loses power.
      channel.force(true);
    }
  }

  private EventChain chain() throws IOException {
    if (chain == null) {
      chain = EventChain.keyedBy(keyFile);
    }
    return chain;
  }

  /**
   * The three fields the chain covers: the time with its offset, what happened, and who it was
   * about.
   *
   * <p>The timestamp carries a timezone because a bare local time is unreadable a year later on a
   * machine that has since moved.
   */
  private static String entryFor(AuthenticationEvent event) {
    return quoted(
            ZonedDateTime.ofInstant(event.at(), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        + ","
        + quoted(event.type().name())
        + ","
        + quoted(onOneLine(event.subject()));
  }

  /** The chain value a line carries: its first field, up to the first comma. */
  private static String chainValueOf(String line) {
    return unquoted(line.substring(0, endOfTheFirstField(line)));
  }

  /** Everything the chain value covers: the line after its first field. */
  private static String entryIn(String line) {
    int end = endOfTheFirstField(line);
    return end < line.length() ? line.substring(end + 1) : "";
  }

  /**
   * Where a line's first field stops. A chain value is Base64, so the first comma is its end — and
   * a line this build did not write is measured the same way rather than refused, because refusing
   * one would stop the service recording anything after it.
   */
  private static int endOfTheFirstField(String line) {
    int comma = line.indexOf(',');
    return comma < 0 ? line.length() : comma;
  }

  private static String quoted(String field) {
    return '"' + field.replace("\"", "\"\"") + '"';
  }

  private static String unquoted(String field) {
    if (field.length() >= 2 && field.startsWith("\"") && field.endsWith("\"")) {
      return field.substring(1, field.length() - 1).replace("\"\"", "\"");
    }
    return field;
  }

  /** Every control character becomes a space, so that one event stays one line. */
  private static String onOneLine(String subject) {
    StringBuilder folded = new StringBuilder(subject.length());
    subject.codePoints().forEach(point -> folded.appendCodePoint(printable(point)));
    return folded.toString();
  }

  private static int printable(int codePoint) {
    return Character.isISOControl(codePoint) ? ' ' : codePoint;
  }
}
