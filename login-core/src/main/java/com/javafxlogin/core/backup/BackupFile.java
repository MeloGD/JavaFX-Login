package com.javafxlogin.core.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javafxlogin.core.account.BackedUpAccount;
import com.javafxlogin.core.account.FailedAuthentications;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.crypto.AesGcm;
import com.javafxlogin.core.crypto.PasswordDerivedKey;
import com.javafxlogin.core.store.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Backup as it exists on a disk: a header nobody needs a password to read, and everything else
 * sealed under one.
 *
 * <p>ADR-0006 chose portability over machine binding, so this file is the whole of the protection.
 * Nothing in it is derived from the machine that wrote it — no DPAPI, no keyring, no MachineKey —
 * because a backup that only restores where it was made is useless in the one situation a backup
 * exists for. What is left is a password an Administrator typed at the moment of the export and
 * Argon2id standing in front of it, and that is not nothing: the cost parameters travel in the
 * header so that raising them later does not strand the files written before.
 *
 * <p>The header is in the clear because it has to be — the salt and the cost are what a reader needs
 * before they can derive anything — and it says nothing. Not how many Accounts, not one name, not
 * which deployment. Everything that is a fact about somebody is on the inside.
 *
 * <p><b>A file this password does not open and a file somebody edited are the same answer.</b> That
 * is not vagueness, it is what authenticated encryption knows: GCM's tag fails identically for a key
 * that is wrong and for a ciphertext that changed, and a reader that reported which was which would
 * be telling whoever is guessing that they had the right file.
 */
public final class BackupFile {

  /** What this file says it is. A file that does not say this is not one of these. */
  private static final String FORMAT = "javafx-login-backup";

  /**
   * The shape of the file, which is not the shape of the CredentialStore inside it. This number
   * changes if the header or the sealing ever does; the schema version inside changes when the
   * Accounts do, and confusing the two is how a build ends up reading a file it only half
   * understands.
   */
  private static final int FORMAT_VERSION = 1;

  /** Sixteen bytes, fresh per Backup: two exports of the same store share no derived key. */
  private static final int SALT_BYTES = 16;

  /**
   * What this build will spend deriving a key from a file it was handed.
   *
   * <p>The cost travels in the header so that it can be raised, which also means the header is
   * somewhere a number comes from that this process then allocates against. A file claiming a
   * gibibyte and a half of Argon2 memory is refused rather than attempted: the Administrator asked
   * to read a backup, not to have the privileged process fall over.
   */
  private static final int MOST_MEMORY_KIB = 1024 * 1024;

  private static final int MOST_ITERATIONS = 20;

  private static final int MOST_LANES = 16;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private BackupFile() {}

  /**
   * Writes the Backup, sealed under the password, to a file that must not already exist.
   *
   * <p>Owner-only and created new, like everything else the AuthenticationService writes and for the
   * sharper of the two reasons: this one holds every password hash in the deployment. Refusing an
   * existing file is the operating system's refusal rather than a check made first — a check made
   * first is one a symbolic link planted in between goes round.
   *
   * <p>A write that fails halfway leaves nothing behind. Half a Backup is worse than none, because
   * it is the kind of none that somebody finds out about on the day they need it.
   *
   * @param parameters the Argon2id cost to derive this file's key at, recorded in the header
   * @throws java.nio.file.FileAlreadyExistsException if something is already at the destination
   * @throws IOException if the file cannot be written
   */
  public static Backup writeTo(
      Path destination, BackupContents contents, char[] password, Argon2Parameters parameters)
      throws IOException {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(contents, "contents");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(parameters, "parameters");

    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);

    byte[] plaintext = write(sealable(contents));
    PasswordDerivedKey key = PasswordDerivedKey.from(password, salt, parameters);
    AesGcm.Sealed sealed;
    try {
      sealed = AesGcm.seal(key.material(), plaintext, random);
    } finally {
      key.destroy();
      Arrays.fill(plaintext, (byte) 0);
    }

    OwnerOnlyFiles.createNew(destination);
    try {
      Files.write(destination, write(header(salt, parameters, sealed)), StandardOpenOption.WRITE);
    } catch (IOException e) {
      Files.deleteIfExists(destination);
      throw e;
    }
    return contents.summary();
  }

  /**
   * Reads a Backup, or answers that this password does not open this file.
   *
   * <p>The empty answer covers every way of not being a Backup this build can read: a wrong
   * password, a file somebody edited, a file that was never one of these, and a file written by a
   * later build whose header this one does not understand. They are one answer because they are one
   * remedy — find the right file, or type the right password — and because separating them would
   * mean saying, to somebody working through a wordlist, which of their guesses was closer.
   *
   * @throws IOException if the file cannot be read at all, which is not the same as not opening
   */
  public static Optional<BackupContents> readFrom(Path source, char[] password) throws IOException {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(password, "password");

    byte[] file = Files.readAllBytes(source);
    try {
      ObjectNode header = headerIn(file);
      byte[] salt = bytes(header, "salt");
      Argon2Parameters parameters = cost(header);

      PasswordDerivedKey key = PasswordDerivedKey.from(password, salt, parameters);
      Optional<byte[]> plaintext;
      try {
        plaintext =
            AesGcm.open(key.material(), bytes(header, "nonce"), bytes(header, "contents"));
      } finally {
        key.destroy();
      }
      if (plaintext.isEmpty()) {
        return Optional.empty();
      }
      try {
        return Optional.of(contentsIn(plaintext.get()));
      } finally {
        Arrays.fill(plaintext.get(), (byte) 0);
      }
    } catch (NotABackupThisBuildReads e) {
      return Optional.empty();
    }
  }

  private static ObjectNode header(
      byte[] salt, Argon2Parameters parameters, AesGcm.Sealed sealed) {
    ObjectNode header =
        MAPPER.createObjectNode().put("format", FORMAT).put("formatVersion", FORMAT_VERSION);
    header.set(
        "argon2id",
        MAPPER
            .createObjectNode()
            .put("memoryKib", parameters.memoryKib())
            .put("iterations", parameters.iterations())
            .put("parallelism", parameters.parallelism()));
    return header
        .put("salt", encode(salt))
        .put("nonce", encode(sealed.nonce()))
        .put("contents", encode(sealed.ciphertext()));
  }

  private static ObjectNode headerIn(byte[] file) {
    ObjectNode header = object(read(file));
    if (!FORMAT.equals(text(header, "format")) || whole(header, "formatVersion") != FORMAT_VERSION) {
      throw new NotABackupThisBuildReads();
    }
    return header;
  }

  /**
   * The Argon2id cost this file was written at, within what this build will spend on one.
   *
   * <p>Read from the file rather than assumed, so that a Backup written before a deployment raised
   * its parameters still opens. Bounded, so that a file cannot ask this process for more memory than
   * the machine has.
   */
  private static Argon2Parameters cost(ObjectNode header) {
    ObjectNode argon2id = object(header.get("argon2id"));
    int memoryKib = whole(argon2id, "memoryKib");
    int iterations = whole(argon2id, "iterations");
    int parallelism = whole(argon2id, "parallelism");
    if (memoryKib > MOST_MEMORY_KIB || iterations > MOST_ITERATIONS || parallelism > MOST_LANES) {
      throw new NotABackupThisBuildReads();
    }
    try {
      return new Argon2Parameters(memoryKib, iterations, parallelism, AesGcm.KEY_BYTES);
    } catch (IllegalArgumentException e) {
      throw new NotABackupThisBuildReads();
    }
  }

  private static ObjectNode sealable(BackupContents contents) {
    ArrayNode accounts = MAPPER.createArrayNode();
    for (BackedUpAccount account : contents.accounts()) {
      accounts.add(sealable(account));
    }
    ObjectNode configuration = MAPPER.createObjectNode();
    contents.configuration().forEach(configuration::put);

    ObjectNode sealable = MAPPER.createObjectNode().put("schemaVersion", contents.schemaVersion());
    sealable.set("accounts", accounts);
    sealable.set("configuration", configuration);
    return sealable;
  }

  /**
   * One Account, field by field rather than by handing the record to Jackson.
   *
   * <p>The same rule the messages on the socket follow, for the same reason turned inside out: what
   * goes into a Backup is what this method names, so a later build that adds a column to the store
   * has to come here before that column can travel — and a column that must <em>not</em> travel,
   * which is what the enrolment ones are, cannot start travelling by accident.
   */
  private static ObjectNode sealable(BackedUpAccount account) {
    ObjectNode written =
        MAPPER
            .createObjectNode()
            .put("name", account.name())
            .put("role", account.role().name())
            .put("passwordStrength", account.passwordStrength().name())
            .put(
                "createdAt",
                account.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("failedAuthentications", account.failures().inARow());
    // Written as an explicit null where the Account was awaiting enrolment, for the reason every
    // absent field in this product is: a field that is missing is a file this build does not read,
    // and a field that says "there was no password" is one it does.
    account
        .passwordHash()
        .ifPresentOrElse(
            hash -> written.put("passwordHash", hash), () -> written.putNull("passwordHash"));
    moment(written, "passwordResetAt", account.passwordResetAt());
    moment(written, "refusedUntil", account.failures().refusedUntil());
    account
        .languagePreference()
        .ifPresentOrElse(
            language -> written.put("languagePreference", language.toLanguageTag()),
            () -> written.putNull("languagePreference"));
    return written;
  }

  private static BackupContents contentsIn(byte[] plaintext) {
    ObjectNode sealed = object(read(plaintext));
    JsonNode listed = sealed.get("accounts");
    if (!(listed instanceof ArrayNode array)) {
      throw new NotABackupThisBuildReads();
    }
    List<BackedUpAccount> accounts = new ArrayList<>();
    for (JsonNode element : array) {
      accounts.add(accountIn(object(element)));
    }
    ObjectNode configured = object(sealed.get("configuration"));
    Map<String, String> configuration = new LinkedHashMap<>();
    configured
        .properties()
        .forEach(setting -> configuration.put(setting.getKey(), text(configured, setting.getKey())));
    return new BackupContents(whole(sealed, "schemaVersion"), accounts, configuration);
  }

  private static BackedUpAccount accountIn(ObjectNode account) {
    try {
      return new BackedUpAccount(
          text(account, "name"),
          Role.valueOf(text(account, "role")),
          passwordHash(account),
          PasswordStrength.valueOf(text(account, "passwordStrength")),
          OffsetDateTime.parse(text(account, "createdAt")),
          moment(account, "passwordResetAt"),
          language(account),
          new FailedAuthentications(
              whole(account, "failedAuthentications"), moment(account, "refusedUntil")));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new NotABackupThisBuildReads();
    }
  }

  /** The hash, or nothing at all where the Account was awaiting enrolment when it was copied. */
  private static Optional<String> passwordHash(ObjectNode account) {
    JsonNode value = account.get("passwordHash");
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new NotABackupThisBuildReads();
    }
    return value.isNull() ? Optional.empty() : Optional.of(value.textValue());
  }

  private static void moment(ObjectNode written, String field, Optional<Instant> moment) {
    moment.ifPresentOrElse(
        present -> written.put(field, present.toString()), () -> written.putNull(field));
  }

  private static Optional<Instant> moment(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new NotABackupThisBuildReads();
    }
    return value.isNull() ? Optional.empty() : Optional.of(Instant.parse(value.textValue()));
  }

  private static Optional<Locale> language(ObjectNode account) {
    JsonNode value = account.get("languagePreference");
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new NotABackupThisBuildReads();
    }
    if (value.isNull()) {
      return Optional.empty();
    }
    Locale language = Locale.forLanguageTag(value.textValue());
    if (language.getLanguage().isEmpty()) {
      throw new NotABackupThisBuildReads();
    }
    return Optional.of(language);
  }

  private static ObjectNode object(JsonNode node) {
    if (!(node instanceof ObjectNode object)) {
      throw new NotABackupThisBuildReads();
    }
    return object;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new NotABackupThisBuildReads();
    }
    return value.textValue();
  }

  /** A whole number that is not negative, which is what every number in this file is. */
  private static int whole(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
        || value.intValue() < 0) {
      throw new NotABackupThisBuildReads();
    }
    return value.intValue();
  }

  private static byte[] bytes(ObjectNode node, String field) {
    try {
      return Base64.getDecoder().decode(text(node, field));
    } catch (IllegalArgumentException e) {
      throw new NotABackupThisBuildReads();
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static JsonNode read(byte[] json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new NotABackupThisBuildReads();
    }
  }

  /**
   * Not a message to anybody: it is how the many ways of not being a readable Backup reach the one
   * place that turns all of them into the same empty answer. It never leaves this class, which is
   * why it carries nothing — anything it carried would be a detail somebody eventually reported.
   */
  private static final class NotABackupThisBuildReads extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private NotABackupThisBuildReads() {
      super(null, null, false, false);
    }
  }

  /**
   * Writes a tree this class built. It cannot fail to serialise — everything in it is a string, a
   * number or a flag put there above — so a failure here is a bug rather than something a caller
   * could do anything about, and it is not folded into the {@link IOException} that means the disk
   * would not take the file.
   */
  private static byte[] write(ObjectNode tree) {
    try {
      return MAPPER.writeValueAsBytes(tree);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write a Backup this class built", e);
    }
  }
}
