package com.javafxlogin.core.store;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.BackedUpAccount;
import com.javafxlogin.core.account.Enrolment;
import com.javafxlogin.core.account.FailedAuthentications;
import com.javafxlogin.core.account.LockoutPolicy;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.session.InactivityPeriod;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The record of every Account, its password hash and the configuration of the application.
 *
 * <p>Only the AuthenticationService reads it. That is enforced on disk rather than in code: the
 * file is created, and reasserted on every open, with owner-only permissions, so the unprivileged
 * account the graphical client runs as can read neither the account list nor a single hash. An
 * upgrade that quietly loosened this would undo the project's only real security property.
 */
public final class CredentialStore implements AutoCloseable {

  /** The configured setting's name, as V003 writes it. */
  private static final String INACTIVITY_PERIOD = "session.inactivity_period";

  /** The LockoutPolicy's two settings, as V004 writes them. */
  private static final String FAILURES_THAT_LOCK = "lockout.failures_that_lock";

  private static final String LOCKOUT_LASTS_FOR = "lockout.lasts_for";

  /** How long an enrolment secret stays usable, as V005 writes it. */
  private static final String ENROLMENT_SECRET_LASTS_FOR = "enrolment.secret_lasts_for";

  private final Path file;
  private final Connection connection;

  private CredentialStore(Path file, Connection connection) {
    this.file = file;
    this.connection = connection;
  }

  /**
   * Opens the store at the given path, creating and migrating it if it does not exist yet.
   *
   * @throws SchemaTooNewException if the store was written by a build that understood more
   * @throws CredentialStoreException if the file cannot be created or opened
   */
  public static CredentialStore openOrCreate(Path file) {
    Objects.requireNonNull(file, "file");
    createOwnerOnly(file);

    Connection connection = connect(file);
    try {
      SchemaMigrations.applyTo(connection);
    } catch (SQLException e) {
      throw closeAfter(
          connection,
          new CredentialStoreException("could not migrate the CredentialStore at " + file, e));
    } catch (RuntimeException e) {
      throw closeAfter(connection, e);
    }
    return new CredentialStore(file, connection);
  }

  /** Whether the single Administrator has been created. */
  public boolean hasAdministrator() {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM accounts WHERE role = ? LIMIT 1")) {
      statement.setString(1, Role.ADMINISTRATOR.name());
      try (ResultSet results = statement.executeQuery()) {
        return results.next();
      }
    } catch (SQLException e) {
      throw new CredentialStoreException("could not look for the Administrator in " + file, e);
    }
  }

  /** Finds an Account by the exact name typed at the login prompt. */
  public Optional<Account> findByName(String name) {
    Objects.requireNonNull(name, "name");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name, role, password_hash, password_strength FROM accounts WHERE name = ?")) {
      statement.setString(1, name);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Account(
                results.getString("name"),
                Role.valueOf(results.getString("role")),
                Optional.ofNullable(results.getString("password_hash")),
                PasswordStrength.valueOf(results.getString("password_strength"))));
      }
    } catch (SQLException e) {
      throw new CredentialStoreException("could not look up an Account in " + file, e);
    }
  }

  /**
   * Every Account this deployment holds, as the administration panel lists them, in the order a
   * person reads them.
   *
   * <p>The one query that reads the store as a whole, and the only one that hands anything about
   * every Account to a caller — so what it selects is written out column by column rather than as a
   * {@code *}: the password hash is on that table, and a query that took the whole row would put it
   * one careless field away from a response that crosses the socket.
   *
   * <p>What is not answered here is the Lockout. The store holds the moment a refusal runs out and
   * has neither a clock nor the LockoutPolicy to read it against, so every summary comes back
   * saying nothing about one, and the AuthenticationService fills it in with the same arithmetic
   * that refuses an attempt at the login screen.
   *
   * @throws CredentialStoreException if a row names a Role, a band or a LanguagePreference this
   *     build does not read — a store edited by hand is not guessed at
   */
  public List<AccountSummary> accounts() {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name, role, password_strength, password_hash IS NULL AS awaiting_enrolment,"
                + " language_preference FROM accounts ORDER BY name")) {
      try (ResultSet results = statement.executeQuery()) {
        List<AccountSummary> accounts = new ArrayList<>();
        while (results.next()) {
          accounts.add(
              new AccountSummary(
                  results.getString("name"),
                  Role.valueOf(results.getString("role")),
                  bandIn(results),
                  languagePreferenceIn(results)));
        }
        return List.copyOf(accounts);
      }
    } catch (SQLException | IllegalArgumentException e) {
      throw new CredentialStoreException("could not list the Accounts in " + file, e);
    }
  }

  /**
   * The coarse band of an Account's password, or nothing at all where it has none yet.
   *
   * <p>Whether it has one is asked of the schema rather than read out of it: the query selects
   * {@code password_hash IS NULL} and never the hash, which is the one column on this table that
   * must not leave the process this class runs in.
   *
   * @throws IllegalArgumentException if the row names a band this build does not read
   */
  private static Optional<PasswordStrength> bandIn(ResultSet results) throws SQLException {
    if (results.getBoolean("awaiting_enrolment")) {
      return Optional.empty();
    }
    return Optional.of(PasswordStrength.valueOf(results.getString("password_strength")));
  }

  /**
   * The language an Account's holder reads, as V006 writes it: a BCP 47 tag, or nothing at all
   * where they have said nothing and the machine's own locale answers for them.
   *
   * @throws IllegalArgumentException if the column holds something that names no language, which
   *     is not read as "said nothing" — an Administrator would then be told this person expressed
   *     no preference while the store says they did
   */
  private static Optional<Locale> languagePreferenceIn(ResultSet results) throws SQLException {
    String tag = results.getString("language_preference");
    if (tag == null) {
      return Optional.empty();
    }
    Locale preference = Locale.forLanguageTag(tag);
    if (preference.getLanguage().isEmpty()) {
      throw new IllegalArgumentException("no language is named by the tag " + tag);
    }
    return Optional.of(preference);
  }

  /**
   * The language one Account's holder reads, or nothing at all where they have said nothing.
   *
   * <p>Asked about one Account rather than read out of the whole list, because this is what the
   * AuthenticationService answers an admission with: the person has just proved they hold this
   * Account, and the language they read is the one thing about it their client is handed.
   *
   * @throws CredentialStoreException if the column holds something that names no language
   */
  public Optional<Locale> languagePreferenceOf(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT language_preference FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        return results.next() ? languagePreferenceIn(results) : Optional.empty();
      }
    } catch (SQLException | IllegalArgumentException e) {
      throw new CredentialStoreException("could not read a LanguagePreference in " + file, e);
    }
  }

  /**
   * Records the language an Account's holder reads, or that they have said nothing, and answers
   * whether there was an Account to record it against.
   *
   * <p>What is written is the BCP 47 tag and nothing else. Which languages a build ships is not
   * this store's business and is not the AuthenticationService's either — the bundles are in the
   * client, and a store that refused a tag no bundle answered to would make adding a language a
   * change to the privileged process.
   *
   * <p>Saying nothing is written as NULL rather than as a tag meaning "the machine's", because the
   * two are different facts: an Account that follows the machine follows whichever machine it is
   * being read on, and a tag would freeze that to the one it was chosen on.
   */
  public boolean setLanguagePreference(String accountName, Optional<Locale> preference) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(preference, "preference");
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE accounts SET language_preference = ? WHERE name = ?")) {
      statement.setString(1, preference.map(Locale::toLanguageTag).orElse(null));
      statement.setString(2, accountName);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record a LanguagePreference in " + file, e);
    }
  }

  /**
   * Records a new Account that has a password of its own, which is the Administrator's and no other:
   * every Operator is created awaiting enrolment.
   *
   * @throws CredentialStoreException if the name is taken, a second Administrator was attempted, or
   *     the Account has no password — the schema refuses an Account with neither a password nor an
   *     outstanding enrolment, and so an Account nobody could ever use never reaches the file
   */
  public void insert(Account account) {
    Objects.requireNonNull(account, "account");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts (name, role, password_hash, password_strength, created_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, account.name());
      statement.setString(2, account.role().name());
      statement.setString(3, account.passwordHash().orElse(null));
      statement.setString(4, account.passwordStrength().name());
      statement.setString(5, createdNow());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record an Account in " + file, e);
    }
  }

  /**
   * Records a new Account with no password at all and the one-time secret that will let the person
   * who uses it choose one.
   *
   * <p>One statement and not two, because an Account with neither a password nor an enrolment is an
   * Account nobody can use and no Administrator can rescue. The schema refuses that row, so writing
   * it in two steps would be writing a row the store would not take.
   *
   * @throws CredentialStoreException if the name is taken or a second Administrator was attempted
   */
  public void insertAwaitingEnrolment(String name, Role role, Enrolment enrolment) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(enrolment, "enrolment");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts"
                + " (name, role, enrolment_secret_hash, enrolment_issued_at, created_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, name);
      statement.setString(2, role.name());
      statement.setString(3, enrolment.secretHash());
      statement.setString(4, enrolment.issuedAt().toString());
      statement.setString(5, createdNow());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record an Account in " + file, e);
    }
  }

  /**
   * The enrolment an Account is waiting on, or empty where it has a password instead — which is
   * also the answer for a name no Account holds.
   *
   * @throws CredentialStoreException if the moment it was issued is not one this build wrote
   */
  public Optional<Enrolment> enrolmentOf(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT enrolment_secret_hash, enrolment_issued_at FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        String secretHash = results.next() ? results.getString("enrolment_secret_hash") : null;
        if (secretHash == null) {
          return Optional.empty();
        }
        return Optional.of(
            new Enrolment(secretHash, Instant.parse(results.getString("enrolment_issued_at"))));
      }
    } catch (SQLException | DateTimeParseException e) {
      throw new CredentialStoreException(
          "could not read what an Account is enrolling with in " + file, e);
    }
  }

  /**
   * Takes an Account's password away and gives it an enrolment to replace it, in the one statement
   * that does both.
   *
   * <p>The password goes first and immediately, which is the whole of ASVS 5.0 §6.4.6 as this system
   * implements it: a reset that left the old hash working until the new password arrived would be a
   * reset an Administrator can start and quietly abandon, and the Operator would never know it
   * happened.
   *
   * @param passwordResetAt when the password this replaces was taken away, for the Operator to be
   *     told at their next login; empty where there was no password to take — a secret re-issued to
   *     an Account still awaiting its first enrolment is not news anybody is owed, and whatever the
   *     Account was already owed is left where it was
   */
  public void awaitEnrolment(
      String accountName, Enrolment enrolment, Optional<Instant> passwordResetAt) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(enrolment, "enrolment");
    Objects.requireNonNull(passwordResetAt, "passwordResetAt");
    // COALESCE rather than two statements or two spellings of one: given nothing, it leaves
    // whatever the Account was already owed exactly where it was.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET password_hash = NULL, password_strength = 'WEAK',"
                + " enrolment_secret_hash = ?, enrolment_issued_at = ?,"
                + " password_reset_at = COALESCE(?, password_reset_at)"
                + " WHERE name = ?")) {
      statement.setString(1, enrolment.secretHash());
      statement.setString(2, enrolment.issuedAt().toString());
      statement.setString(3, passwordResetAt.map(Instant::toString).orElse(null));
      statement.setString(4, accountName);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not issue an enrolment in " + file, e);
    }
  }

  /**
   * Records the password an Operator chose for themselves, and consumes the secret that let them.
   *
   * <p>One statement again, and for the sharper of the two reasons: the secret is one-time, and a
   * build that wrote the password first and cleared the secret afterwards would leave a window in
   * which the secret is still good and the password already works.
   */
  public void completeEnrolment(
      String accountName, String passwordHash, PasswordStrength strength) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(strength, "strength");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET password_hash = ?, password_strength = ?,"
                + " enrolment_secret_hash = NULL, enrolment_issued_at = NULL"
                + " WHERE name = ?")) {
      statement.setString(1, passwordHash);
      statement.setString(2, strength.name());
      statement.setString(3, accountName);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not complete an enrolment in " + file, e);
    }
  }

  /**
   * Records the password an Account holder chose to replace the one it had.
   *
   * <p>Unlike {@link #completeEnrolment} there is no secret to consume here and no enrolment to end:
   * this Account had a password, offered it, and now has another. The two are separate methods
   * because they are separate facts, and a build that reused one for the other would be writing
   * "an enrolment completed" into the store every time somebody rotated a password.
   */
  public void recordChosenPassword(
      String accountName, String passwordHash, PasswordStrength strength) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(strength, "strength");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET password_hash = ?, password_strength = ? WHERE name = ?")) {
      statement.setString(1, passwordHash);
      statement.setString(2, strength.name());
      statement.setString(3, accountName);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record a chosen password in " + file, e);
    }
  }

  /**
   * Removes an Account entirely, and answers whether there was one to remove.
   *
   * <p>Everything this store holds about the Account goes with the row: its hash, what it has failed,
   * any Lockout, any outstanding enrolment and any reset it was owed being told about. What does not
   * live here is the Account's wrapped copy of the DataKey, which is in the SecretVault — the caller
   * destroys that first, because a wrap left behind by a delete that half-worked would be Vault
   * access reachable again by creating an Account under the same name.
   */
  public boolean delete(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new CredentialStoreException("could not remove an Account from " + file, e);
    }
  }

  /**
   * When an Administrator last took this Account's password away, where the Operator has not yet
   * been told about it.
   *
   * @throws CredentialStoreException if the moment is not one this build wrote
   */
  public Optional<Instant> passwordResetAt(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT password_reset_at FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        String resetAt = results.next() ? results.getString("password_reset_at") : null;
        return Optional.ofNullable(resetAt).map(Instant::parse);
      }
    } catch (SQLException | DateTimeParseException e) {
      throw new CredentialStoreException("could not read what an Account is owed in " + file, e);
    }
  }

  /** The Operator has been told their password was reset: it is news, and news is said once. */
  public void forgetPasswordReset(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET password_reset_at = NULL WHERE name = ?")) {
      statement.setString(1, accountName);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record what an Account was told in " + file, e);
    }
  }

  private static String createdNow() {
    return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  /**
   * Which schema this file is at, which is what a Backup names so that a restore is never a guess.
   *
   * <p>It is the same number {@link SchemaMigrations} raises, read back off the file rather than
   * assumed from the build: the service migrates on open, so this is what the rows about to be
   * copied are actually shaped like.
   */
  public int schemaVersion() {
    try {
      return NumberedMigrations.userVersionOf(connection);
    } catch (SQLException e) {
      throw new CredentialStoreException("could not read the schema version of " + file, e);
    }
  }

  /**
   * Every Account a Backup carries, which is every Account — with what each holds, and without what
   * each is waiting for.
   *
   * <p>The two enrolment columns are not selected, which is issue #14's third criterion enforced by
   * the query rather than by whoever writes the next one: a secret somebody is carrying to a machine
   * that no longer exists must not be resurrected on a replacement. The Account itself is not
   * transient and does travel — an Operator whose password an Administrator took away yesterday is a
   * person, and a Backup that dropped them because of when it was taken would be losing them to the
   * timing of a reset.
   *
   * <p>Written out column by column for the reason {@link #accounts()} is, and with the opposite
   * conclusion: this query <em>does</em> select the password hash, because a Backup that did not
   * carry it would restore a deployment nobody could log in to. What keeps that safe is not this
   * query but where it goes — into a file sealed under a password, never into a response.
   *
   * @throws CredentialStoreException if a row names a Role, a band, a language or a moment this
   *     build does not read — a store edited by hand is not guessed at
   */
  public List<BackedUpAccount> backedUpAccounts() {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name, role, password_hash, password_strength, created_at, password_reset_at,"
                + " language_preference, failed_authentications, refused_until FROM accounts"
                + " ORDER BY name")) {
      try (ResultSet results = statement.executeQuery()) {
        List<BackedUpAccount> accounts = new ArrayList<>();
        while (results.next()) {
          accounts.add(backedUpAccountIn(results));
        }
        return List.copyOf(accounts);
      }
    } catch (SQLException | IllegalArgumentException | DateTimeParseException e) {
      throw new CredentialStoreException("could not read the Accounts to back up in " + file, e);
    }
  }

  private static BackedUpAccount backedUpAccountIn(ResultSet results) throws SQLException {
    String refusedUntil = results.getString("refused_until");
    String resetAt = results.getString("password_reset_at");
    return new BackedUpAccount(
        results.getString("name"),
        Role.valueOf(results.getString("role")),
        Optional.ofNullable(results.getString("password_hash")),
        PasswordStrength.valueOf(results.getString("password_strength")),
        OffsetDateTime.parse(results.getString("created_at")),
        Optional.ofNullable(resetAt).map(Instant::parse),
        languagePreferenceIn(results),
        new FailedAuthentications(
            results.getInt("failed_authentications"),
            Optional.ofNullable(refusedUntil).map(Instant::parse)));
  }

  /**
   * Every configured setting, which is the other half of what a Backup carries.
   *
   * <p>Read as the names and values they are rather than as the settings this build happens to know
   * about: a Backup written by this build and restored by this build carries whatever the migrations
   * put here, and a method that listed the four it can name would silently drop the fifth the day
   * somebody adds one.
   */
  public Map<String, String> configuration() {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT name, value FROM configuration ORDER BY name");
        ResultSet results = statement.executeQuery()) {
      Map<String, String> settings = new LinkedHashMap<>();
      while (results.next()) {
        settings.put(results.getString("name"), results.getString("value"));
      }
      return Map.copyOf(settings);
    } catch (SQLException e) {
      throw new CredentialStoreException("could not read the configuration in " + file, e);
    }
  }

  /**
   * Replaces every Account and every setting with the ones a Backup carried, wholesale.
   *
   * <p>ADR-0006 refuses to merge, and this is where that refusal is enforced rather than asserted:
   * both tables are emptied before either is written to, so there is no path through this method
   * that leaves a row from the machine being restored onto beside a row from the Backup. Merging
   * Accounts from two origins produces states nobody can reason about — two people who both believe
   * they hold a name, a Lockout being served against a password that is no longer there.
   *
   * <p>One transaction, so that the store is either the Backup's or exactly what it was. A restore
   * that failed halfway is the one outcome worse than a restore that failed: the Administrator would
   * be looking at a deployment that is neither, with no copy of the first half left anywhere.
   *
   * @param anEnrolmentNobodyHolds asked for once per Account that was awaiting enrolment when the
   *     Backup was taken. The schema refuses an Account with neither a password nor an outstanding
   *     enrolment, and the Backup carries no enrolment, so what such an Account is restored as is
   *     one waiting on a secret that was never issued to anybody — which is the honest reading of
   *     its state, and the Administrator issues a real one from the panel
   * @throws CredentialStoreException if the Backup does not make a store this schema will hold — two
   *     Administrators, a repeated name — in which case nothing has been written
   */
  public void replaceEverythingWith(
      List<BackedUpAccount> accounts,
      Map<String, String> configuration,
      Supplier<Enrolment> anEnrolmentNobodyHolds) {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(anEnrolmentNobodyHolds, "anEnrolmentNobodyHolds");
    boolean autoCommit = autoCommitOf();
    try {
      connection.setAutoCommit(false);
      try {
        emptyTheStore();
        for (BackedUpAccount account : accounts) {
          restore(account, anEnrolmentNobodyHolds);
        }
        for (Map.Entry<String, String> setting : configuration.entrySet()) {
          restore(setting.getKey(), setting.getValue());
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new CredentialStoreException("could not restore a Backup into " + file, e);
    }
  }

  private boolean autoCommitOf() {
    try {
      return connection.getAutoCommit();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not read how " + file + " commits", e);
    }
  }

  private void emptyTheStore() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM accounts");
      statement.executeUpdate("DELETE FROM configuration");
    }
  }

  /**
   * Writes one Account back exactly as it was copied, with an enrolment nobody holds where it had no
   * password.
   *
   * <p>The schema's own rule — a password or an outstanding enrolment, never both and never neither
   * — is what shapes this. A Backup carries no enrolment, so an Account that had none of the first
   * needs something in the second, and what goes there is the hash of a secret this machine
   * generated and told nobody. That is not a way in: nobody can offer a secret nobody was given, and
   * the Administrator issues a real one from the panel, which is the same conversation they were
   * going to have anyway.
   */
  private void restore(BackedUpAccount account, Supplier<Enrolment> anEnrolmentNobodyHolds)
      throws SQLException {
    Optional<Enrolment> waitingOn =
        account.isAwaitingEnrolment()
            ? Optional.of(anEnrolmentNobodyHolds.get())
            : Optional.empty();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts (name, role, password_hash, password_strength, created_at,"
                + " password_reset_at, language_preference, failed_authentications, refused_until,"
                + " enrolment_secret_hash, enrolment_issued_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, account.name());
      statement.setString(2, account.role().name());
      statement.setString(3, account.passwordHash().orElse(null));
      statement.setString(4, account.passwordStrength().name());
      statement.setString(5, account.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      statement.setString(6, account.passwordResetAt().map(Instant::toString).orElse(null));
      statement.setString(
          7, account.languagePreference().map(Locale::toLanguageTag).orElse(null));
      statement.setInt(8, account.failures().inARow());
      statement.setString(
          9, account.failures().refusedUntil().map(Instant::toString).orElse(null));
      statement.setString(10, waitingOn.map(Enrolment::secretHash).orElse(null));
      statement.setString(
          11, waitingOn.map(enrolment -> enrolment.issuedAt().toString()).orElse(null));
      statement.executeUpdate();
    }
  }

  private void restore(String setting, String value) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO configuration (name, value) VALUES (?, ?)")) {
      statement.setString(1, setting);
      statement.setString(2, value);
      statement.executeUpdate();
    }
  }

  /**
   * How long a Session may go without Operator activity, as this deployment has it configured.
   *
   * <p>Read rather than remembered, and read again every time it is needed, so that an
   * Administrator changing it changes what happens next rather than what happens after a restart.
   *
   * @throws CredentialStoreException if the setting is missing or is not a period this build wrote
   *     — a store edited by hand is not guessed at
   */
  public InactivityPeriod inactivityPeriod() {
    try {
      return InactivityPeriod.parse(setting(INACTIVITY_PERIOD));
    } catch (IllegalArgumentException e) {
      throw new CredentialStoreException(
          "could not read how long a Session may idle in " + file, e);
    }
  }

  /** Records what an Administrator configured. */
  public void setInactivityPeriod(InactivityPeriod period) {
    Objects.requireNonNull(period, "period");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO configuration (name, value) VALUES (?, ?)"
                + " ON CONFLICT (name) DO UPDATE SET value = excluded.value")) {
      statement.setString(1, INACTIVITY_PERIOD);
      statement.setString(2, period.text());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException(
          "could not record how long a Session may idle in " + file, e);
    }
  }

  /**
   * How many failed authentications in a row lock an Account, and for how long, as this deployment
   * has it configured. Read again every time it is needed, as the InactivityPeriod is.
   *
   * @throws CredentialStoreException if either setting is missing or is not one this build wrote
   */
  public LockoutPolicy lockoutPolicy() {
    try {
      return new LockoutPolicy(
          Integer.parseInt(setting(FAILURES_THAT_LOCK)),
          Duration.parse(setting(LOCKOUT_LASTS_FOR)));
      // Duration.parse refuses with a DateTimeParseException, which is not an
      // IllegalArgumentException. Both are the same thing here: a setting nobody in this build
      // wrote, which is said as one rather than escaping as whichever library refused it.
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new CredentialStoreException("could not read the Lockout policy in " + file, e);
    }
  }

  /**
   * How long a one-time enrolment secret stays usable, as this deployment has it configured. Read
   * again every time it is needed, as the LockoutPolicy is, so that an Administrator who shortens it
   * shortens the secrets already in somebody's pocket.
   *
   * @throws CredentialStoreException if the setting is missing, is not a period this build wrote,
   *     or is no time at all — a secret that expires the moment it is issued is not a policy, it is
   *     an enrolment nobody can complete
   */
  public Duration enrolmentSecretLastsFor() {
    Duration lastsFor;
    try {
      lastsFor = Duration.parse(setting(ENROLMENT_SECRET_LASTS_FOR));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new CredentialStoreException(
          "could not read how long an enrolment secret lasts in " + file, e);
    }
    if (lastsFor.isZero() || lastsFor.isNegative()) {
      throw new CredentialStoreException(
          "an enrolment secret lasts some time, and " + file + " says " + lastsFor, null);
    }
    return lastsFor;
  }

  /**
   * What is remembered about an Account's failed authentications, or empty where there is no such
   * Account — which is also how a caller learns that a name it was given belongs to nobody.
   *
   * @throws CredentialStoreException if the moment a Lockout ends is not one this build wrote
   */
  public Optional<FailedAuthentications> failedAuthenticationsOf(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT failed_authentications, refused_until FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return Optional.empty();
        }
        String refusedUntil = results.getString("refused_until");
        return Optional.of(
            new FailedAuthentications(
                results.getInt("failed_authentications"),
                Optional.ofNullable(refusedUntil).map(Instant::parse)));
      }
    } catch (SQLException | DateTimeParseException e) {
      throw new CredentialStoreException(
          "could not read what an Account has failed in " + file, e);
    }
  }

  /**
   * Records what an Account has failed, replacing whatever was remembered before.
   *
   * <p>Written the moment it happens rather than at shutdown: the service does not run
   * continuously, and a Lockout still sitting in a buffer when it stops is a Lockout that stopping
   * the service cleared. The connection commits each statement as it goes and the store is opened
   * at {@code synchronous = FULL}, so the row is on the disk before this returns.
   */
  public void recordFailedAuthentications(String accountName, FailedAuthentications failures) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(failures, "failures");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE accounts SET failed_authentications = ?, refused_until = ? WHERE name = ?")) {
      statement.setInt(1, failures.inARow());
      statement.setString(2, failures.refusedUntil().map(Instant::toString).orElse(null));
      statement.setString(3, accountName);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException(
          "could not record what an Account has failed in " + file, e);
    }
  }

  /**
   * One configured setting, by name.
   *
   * @throws CredentialStoreException if it is not there — a store edited by hand is not guessed at
   */
  private String setting(String name) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT value FROM configuration WHERE name = ?")) {
      statement.setString(1, name);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          throw new CredentialStoreException("there is no " + name + " in " + file, null);
        }
        return results.getString("value");
      }
    } catch (SQLException e) {
      throw new CredentialStoreException("could not read " + name + " from " + file, e);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not close the CredentialStore at " + file, e);
    }
  }

  private static Connection connect(Path file) {
    try {
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (Statement statement = connection.createStatement()) {
        // The service does not run continuously, so a write must survive it stopping.
        // Journal mode stays at the default rather than WAL, which would leave a second
        // file alongside the store carrying the same secrets.
        statement.execute("PRAGMA foreign_keys = ON");
        statement.execute("PRAGMA synchronous = FULL");
      }
      return connection;
    } catch (SQLException e) {
      throw new CredentialStoreException("could not open the CredentialStore at " + file, e);
    }
  }

  /**
   * Creates the file owner-only before SQLite ever touches it, and reasserts the mode on an
   * existing one. Creating it first matters: a file SQLite creates itself inherits the umask.
   */
  private static void createOwnerOnly(Path file) {
    try {
      OwnerOnlyFiles.createOrReassert(file);
    } catch (IOException e) {
      throw new CredentialStoreException("could not create the CredentialStore at " + file, e);
    }
  }

  /**
   * Closes a connection that failed on the way up and returns the failure to throw. A close that
   * also fails is attached as suppressed rather than raised, so the reason the store would not open
   * is never replaced by the reason it would not shut.
   */
  private static <E extends RuntimeException> E closeAfter(Connection connection, E failure) {
    try {
      connection.close();
    } catch (SQLException e) {
      failure.addSuppressed(e);
    }
    return failure;
  }
}
