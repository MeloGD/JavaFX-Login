package com.javafxlogin.core.store;

import com.javafxlogin.core.account.Account;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT value FROM configuration WHERE name = ?")) {
      statement.setString(1, INACTIVITY_PERIOD);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          throw new CredentialStoreException(
              "there is no " + INACTIVITY_PERIOD + " in " + file, null);
        }
        return InactivityPeriod.parse(results.getString("value"));
      }
    } catch (SQLException | IllegalArgumentException e) {
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
