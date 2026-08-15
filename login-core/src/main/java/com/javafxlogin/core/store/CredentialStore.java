package com.javafxlogin.core.store;

import com.javafxlogin.core.account.Account;
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
                results.getString("password_hash"),
                PasswordStrength.valueOf(results.getString("password_strength"))));
      }
    } catch (SQLException e) {
      throw new CredentialStoreException("could not look up an Account in " + file, e);
    }
  }

  /**
   * Records a new Account.
   *
   * @throws CredentialStoreException if the name is taken, or a second Administrator was attempted
   */
  public void insert(Account account) {
    Objects.requireNonNull(account, "account");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO accounts (name, role, password_hash, password_strength, created_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, account.name());
      statement.setString(2, account.role().name());
      statement.setString(3, account.passwordHash());
      statement.setString(4, account.passwordStrength().name());
      statement.setString(5, ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new CredentialStoreException("could not record an Account in " + file, e);
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
    } catch (IllegalArgumentException e) {
      throw new CredentialStoreException("could not read the Lockout policy in " + file, e);
    }
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
