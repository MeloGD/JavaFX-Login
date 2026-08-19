package com.javafxlogin.core.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * The CredentialStore's numbered migrations.
 *
 * <p>The list lives here and the machinery that applies it lives in {@link NumberedMigrations},
 * which the SecretVault's own set uses too. What is specific to this store is the list and nothing
 * else: the two files are separate by ADR-0004 and their version numbers are separate with them.
 */
public final class SchemaMigrations {

  private static final NumberedMigrations MIGRATIONS =
      NumberedMigrations.of(
          "the CredentialStore",
          List.of(
              "db/migration/V001__initial_schema.sql",
              "db/migration/V002__password_strength.sql",
              "db/migration/V003__configuration.sql",
              "db/migration/V004__lockout.sql",
              "db/migration/V005__enrolment.sql"));

  private SchemaMigrations() {}

  /** The highest schema version this build understands. */
  public static int latestVersion() {
    return MIGRATIONS.latestVersion();
  }

  /**
   * The migration resources, in the order they are applied. Package-private on purpose: nothing
   * outside this package migrates a store, and the schema test lives alongside it.
   */
  static List<String> resourceNames() {
    return MIGRATIONS.resourceNames();
  }

  /**
   * Brings a connection's schema up to {@link #latestVersion()}.
   *
   * @throws SchemaTooNewException if the store was written by a build that understood more
   */
  static void applyTo(Connection connection) throws SQLException {
    MIGRATIONS.applyTo(connection);
  }

  static int userVersionOf(Connection connection) throws SQLException {
    return NumberedMigrations.userVersionOf(connection);
  }
}
