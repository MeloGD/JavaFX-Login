package com.javafxlogin.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.BackedUpAccount;
import com.javafxlogin.core.account.FailedAuthentications;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1, below the service: the Backup file itself.
 *
 * <p>Issue #14 and ADR-0006. What is asserted here is the file and nothing about who may ask for
 * one: that everything which went in comes back out unchanged, that the only way to get it back is
 * the password it was sealed under, and that the part of the file anybody can read without one says
 * nothing about the deployment. Who is allowed to write or restore one is the
 * AuthenticationService's decision and is asserted where that decision is made.
 */
class BackupFileTest {

  /**
   * Deliberately cheap, for the reason the service harness's parameters are: deriving a key from a
   * password is meant to be slow, and a suite that did it at production cost would be asserting how
   * patient the machine is.
   */
  private static final Argon2Parameters CHEAP = new Argon2Parameters(256, 1, 1, 32);

  private static final char[] PASSWORD = "Correct-Horse-Battery-1".toCharArray();

  @TempDir Path directory;

  @Test
  void everythingThatWentInComesBackOut() throws IOException {
    BackupContents written = aDeployment();
    Path file = directory.resolve("backup.jflb");

    Backup backup = BackupFile.writeTo(file, written, PASSWORD, CHEAP);
    BackupContents read = BackupFile.readFrom(file, PASSWORD).orElseThrow();

    assertEquals(new Backup(3, 2), backup);
    assertEquals(written, read);
  }

  /** The summary is the two counts and never a third fact about what is in the file. */
  @Test
  void whatABackupCameToIsTwoCounts() {
    assertEquals(new Backup(3, 2), aDeployment().summary());
  }

  /**
   * An Account that was awaiting enrolment travels without one, which is the shape the file has to
   * be able to say: a hash that is explicitly absent rather than a field that is simply missing.
   */
  @Test
  void anAccountWithNoPasswordComesBackWithNoPassword() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);

    BackedUpAccount waiting =
        BackupFile.readFrom(file, PASSWORD).orElseThrow().accounts().stream()
            .filter(BackedUpAccount::isAwaitingEnrolment)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the Account awaiting enrolment did not travel"));

    assertEquals("rowan.blythe", waiting.name());
    assertEquals(Optional.empty(), waiting.passwordHash());
    assertEquals(Optional.of(Locale.forLanguageTag("es")), waiting.languagePreference());
  }

  @Test
  void aWrongPasswordDoesNotOpenIt() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);

    assertTrue(BackupFile.readFrom(file, "Correct-Horse-Battery-2".toCharArray()).isEmpty());
  }

  /**
   * A file somebody edited answers exactly as a wrong password does, which is what GCM knows and
   * this class refuses to improve on: a reader that told the two apart would be telling whoever is
   * working through a wordlist that they had the right file.
   */
  @Test
  void aFileSomebodyEditedDoesNotOpenEither() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);
    // One character of the sealed payload, still base64 and no longer sound: the sort of damage a
    // copy across a failing disk does, rather than an obviously truncated file.
    char[] edited = Files.readString(file).toCharArray();
    int somewhereInTheCiphertext = Files.readString(file).indexOf("\"contents\":\"") + 20;
    edited[somewhereInTheCiphertext] = edited[somewhereInTheCiphertext] == 'A' ? 'B' : 'A';
    Files.writeString(file, new String(edited));

    assertTrue(BackupFile.readFrom(file, PASSWORD).isEmpty());
  }

  @Test
  void somethingThatWasNeverABackupDoesNotOpen() throws IOException {
    Path notABackup = directory.resolve("shopping-list.txt");
    Files.writeString(notABackup, "milk, bread, a replacement machine");

    assertTrue(BackupFile.readFrom(notABackup, PASSWORD).isEmpty());
  }

  /**
   * The header is in the clear because a reader needs the salt and the cost before they can derive
   * anything. It is therefore the one part of this file that has to be checked for saying too much.
   */
  @Test
  void theHeaderAnybodyCanReadSaysNothingAboutTheDeployment() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);

    String clear = Files.readString(file, StandardCharsets.UTF_8);

    assertFalse(clear.contains("wren.holloway"), () -> "a name is in the clear: " + clear);
    assertFalse(clear.contains("finch.mercer"), () -> "a name is in the clear: " + clear);
    assertFalse(clear.contains("$argon2id"), () -> "a hash is in the clear: " + clear);
    assertFalse(clear.contains("session.inactivity_period"), () -> "a setting is there: " + clear);
  }

  /** A copy of every password hash in the deployment is not a file that inherits the umask. */
  @Test
  void theFileIsReadableOnlyByTheAccountTheServiceRunsAs() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);

    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  /**
   * Refused by the operating system rather than by a check made first, which is the whole point:
   * a check made first is one a symbolic link planted in between goes round.
   */
  @Test
  void itWillNotWriteOverSomethingThatIsAlreadyThere() throws IOException {
    Path file = directory.resolve("backup.jflb");
    Files.writeString(file, "something somebody wanted");

    assertThrows(
        FileAlreadyExistsException.class,
        () -> BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP));
    assertEquals("something somebody wanted", Files.readString(file));
  }

  /**
   * The cost travels so that raising it does not strand the files written before, which means the
   * header is a number this process then allocates against. A file asking for more memory than the
   * machine has is refused rather than attempted.
   */
  @Test
  void aFileDemandingAbsurdArgon2CostIsRefusedRatherThanAttempted() throws IOException {
    Path file = directory.resolve("backup.jflb");
    BackupFile.writeTo(file, aDeployment(), PASSWORD, CHEAP);
    Files.writeString(
        file, Files.readString(file).replace("\"memoryKib\":256", "\"memoryKib\":2000000000"));

    assertTrue(BackupFile.readFrom(file, PASSWORD).isEmpty());
  }

  /** Two exports of the same store share no derived key, because each gets a salt of its own. */
  @Test
  void twoBackupsOfTheSameDeploymentAreNotTheSameBytes() throws IOException {
    Path one = directory.resolve("one.jflb");
    Path other = directory.resolve("other.jflb");

    BackupFile.writeTo(one, aDeployment(), PASSWORD, CHEAP);
    BackupFile.writeTo(other, aDeployment(), PASSWORD, CHEAP);

    assertFalse(
        Files.readString(one).equals(Files.readString(other)),
        "two backups of the same store came out byte for byte identical");
  }

  @Test
  void aDeploymentWithNobodyToAdministerItSaysSo() {
    assertTrue(aDeployment().namesAnAdministrator());
    assertFalse(
        new BackupContents(6, List.of(anOperator()), Map.of()).namesAnAdministrator(),
        "a set of Operators names an Administrator");
  }

  private static BackupContents aDeployment() {
    return new BackupContents(
        6,
        List.of(anAdministrator(), anOperator(), oneAwaitingEnrolment()),
        Map.of("session.inactivity_period", "PT15M", "lockout.failures_that_lock", "5"));
  }

  /**
   * An Operator whose password an Administrator took away. It carries no hash and no enrolment: the
   * Account is not transient even though what it is waiting for is.
   */
  private static BackedUpAccount oneAwaitingEnrolment() {
    return new BackedUpAccount(
        "rowan.blythe",
        Role.OPERATOR,
        Optional.empty(),
        PasswordStrength.WEAK,
        OffsetDateTime.parse("2026-03-05T11:00:00+01:00"),
        Optional.of(Instant.parse("2026-03-05T11:00:00Z")),
        Optional.of(Locale.forLanguageTag("es")),
        FailedAuthentications.none());
  }

  private static BackedUpAccount anAdministrator() {
    return new BackedUpAccount(
        "wren.holloway",
        Role.ADMINISTRATOR,
        "$argon2id$v=19$m=256,t=1,p=1$c2FsdA$aGFzaA",
        PasswordStrength.STRONG,
        OffsetDateTime.parse("2026-03-01T09:00:00+01:00"),
        Optional.empty(),
        Optional.of(Locale.forLanguageTag("es")),
        FailedAuthentications.none());
  }

  /** Everything optional filled in, so that the round trip is asserted on the awkward fields too. */
  private static BackedUpAccount anOperator() {
    return new BackedUpAccount(
        "finch.mercer",
        Role.OPERATOR,
        "$argon2id$v=19$m=256,t=1,p=1$c2FsdDI$aGFzaDI",
        PasswordStrength.ACCEPTABLE,
        OffsetDateTime.parse("2026-03-02T14:30:00+01:00"),
        Optional.of(Instant.parse("2026-03-03T08:00:00Z")),
        Optional.empty(),
        new FailedAuthentications(3, Optional.of(Instant.parse("2026-03-04T08:15:00Z"))));
  }
}
