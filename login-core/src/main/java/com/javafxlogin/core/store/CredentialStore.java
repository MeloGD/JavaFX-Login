package com.javafxlogin.core.store;

import com.javafxlogin.core.account.Account;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

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
