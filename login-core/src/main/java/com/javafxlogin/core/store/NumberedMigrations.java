package com.javafxlogin.core.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * A numbered set of migrations, and the machinery that brings one SQLite file up to date.
 *
 * <p>The list is written out rather than discovered by scanning the classpath, because the order
 * upgrades run in is exactly the thing that must not depend on how a packaged runtime happens to
 * enumerate resources. The version of a file is SQLite's {@code user_version}, set in the same
 * transaction as the migration that raised it.
 *
 * <p>There are two of these, because there are two files: the CredentialStore's set is {@link
 * SchemaMigrations}, and the SecretVault owns its own. They never share a version number — ADR-0004
 * separated the two files precisely because they change at different rates, and a single number
 * across both would put them back on one schedule.
 */
public final class NumberedMigrations {

  private final String fileMigrated;
  private final List<String> resources;

  private NumberedMigrations(String fileMigrated, List<String> resources) {
    this.fileMigrated = Objects.requireNonNull(fileMigrated, "fileMigrated");
    this.resources = List.copyOf(resources);
  }

  /**
   * @param fileMigrated the file these migrate, worded as a message names it — "the SecretVault"
   * @param resources the migration resources, in the order they are applied
   */
  public static NumberedMigrations of(String fileMigrated, List<String> resources) {
    return new NumberedMigrations(fileMigrated, resources);
  }

  /** The highest schema version this build understands. */
  public int latestVersion() {
    return resources.size();
  }

  /** The migration resources, in the order they are applied. */
  public List<String> resourceNames() {
    return resources;
  }

  /**
   * Brings a connection's schema up to {@link #latestVersion()}.
   *
   * @throws SchemaTooNewException if the file was written by a build that understood more
   */
  public void applyTo(Connection connection) throws SQLException {
    int current = userVersionOf(connection);
    if (current > latestVersion()) {
      throw new SchemaTooNewException(fileMigrated, current, latestVersion());
    }
    for (int version = current + 1; version <= latestVersion(); version++) {
      apply(connection, version);
    }
  }

  /** The schema version a connection's file is at, or zero for one nothing has migrated yet. */
  public static int userVersionOf(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("PRAGMA user_version")) {
      return results.next() ? results.getInt(1) : 0;
    }
  }

  private void apply(Connection connection, int version) throws SQLException {
    String sql = read(resources.get(version - 1));
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      // PRAGMA user_version takes no bind parameters; the value is an int this class chose.
      statement.execute("PRAGMA user_version = " + version);
      connection.commit();
    } catch (SQLException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  private static String read(String resource) {
    try (InputStream stream =
        NumberedMigrations.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("migration missing from the build: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read migration " + resource, e);
    }
  }
}
