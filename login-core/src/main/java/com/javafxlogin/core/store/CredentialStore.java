package com.javafxlogin.core.store;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
import java.util.Set;

/**
 * The record of every Account, its password hash and the configuration of the application.
 *
 * <p>Only the AuthenticationService reads it. That is enforced on disk rather than in code: the
 * file is created, and reasserted on every open, with owner-only permissions, so the unprivileged
 * account the graphical client runs as can read neither the account list nor a single hash. An
 * upgrade that quietly loosened this would undo the project's only real security property.
 */
public final class CredentialStore implements AutoCloseable {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");

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
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    try {
      Path directory = file.toAbsolutePath().getParent();
      if (directory != null && !Files.exists(directory)) {
        if (posix) {
          Files.createDirectories(
              directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } else {
          Files.createDirectories(directory);
        }
      }
      if (!Files.exists(file)) {
        if (posix) {
          Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
          Files.createFile(file);
        }
      }
      if (posix) {
        Files.setPosixFilePermissions(file, OWNER_ONLY);
      } else {
        restrictWithoutPosix(file);
      }
    } catch (IOException e) {
      throw new CredentialStoreException("could not create the CredentialStore at " + file, e);
    }
  }

  /**
   * Best-effort narrowing where POSIX modes do not exist.
   *
   * <p>It deliberately does not fail when the platform refuses these calls, because it is not what
   * protects the store there: on Windows the store lives inside a directory the installer has
   * already restricted by ACL, and {@code setReadable(false, false)} is documented to return false
   * rather than take effect. Failing here would stop the service starting at all instead of
   * starting it with a weaker mode.
   *
   * <p>Designed, unbuilt and unverified: no Windows machine exists for this project yet, and none
   * of this may be reported as working on one.
   */
  private static void restrictWithoutPosix(Path file) {
    java.io.File asFile = file.toFile();
    asFile.setReadable(false, false);
    asFile.setWritable(false, false);
    asFile.setReadable(true, true);
    asFile.setWritable(true, true);
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
