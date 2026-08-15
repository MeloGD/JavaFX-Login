package com.javafxlogin.core.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The CredentialStore's numbered migrations.
 *
 * <p>The migration list is written out rather than discovered by scanning the classpath, because
 * the order upgrades run in is exactly the thing that must not depend on how a packaged runtime
 * happens to enumerate resources. The version of a store is SQLite's {@code user_version}, set in
 * the same transaction as the migration that raised it.
 */
public final class SchemaMigrations {

  private static final List<String> MIGRATIONS =
      List.of(
          "db/migration/V001__initial_schema.sql",
          "db/migration/V002__password_strength.sql",
          "db/migration/V003__configuration.sql",
          "db/migration/V004__lockout.sql");

  private SchemaMigrations() {}

  /** The highest schema version this build understands. */
  public static int latestVersion() {
    return MIGRATIONS.size();
  }

  /**
   * The migration resources, in the order they are applied. Package-private on purpose: nothing
   * outside this package migrates a store, and the schema test lives alongside it.
   */
  static List<String> resourceNames() {
    return MIGRATIONS;
  }

  /**
   * Brings a connection's schema up to {@link #latestVersion()}.
   *
   * @throws SchemaTooNewException if the store was written by a build that understood more
   */
  static void applyTo(Connection connection) throws SQLException {
    int current = userVersionOf(connection);
    if (current > latestVersion()) {
      throw new SchemaTooNewException(current, latestVersion());
    }
    for (int version = current + 1; version <= latestVersion(); version++) {
      apply(connection, version);
    }
  }

  private static void apply(Connection connection, int version) throws SQLException {
    String sql = read(MIGRATIONS.get(version - 1));
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

  static int userVersionOf(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("PRAGMA user_version")) {
      return results.next() ? results.getInt(1) : 0;
    }
  }

  private static String read(String resource) {
    try (InputStream stream =
        SchemaMigrations.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("migration missing from the build: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read migration " + resource, e);
    }
  }
}
