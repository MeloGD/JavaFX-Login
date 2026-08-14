package com.javafxlogin.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.authentication.AuthenticationService;
import com.javafxlogin.core.harness.ServiceHarness;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CredentialStore's schema: versioned, migrated by number, and refused when it is too new. */
class CredentialStoreSchemaTest {

  @TempDir Path directory;

  @Test
  void aFreshStoreIsMigratedToTheLatestVersion() {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertEquals(
        SchemaMigrations.latestVersion(), userVersionOf(ServiceHarness.storeFileIn(directory)));
  }

  /**
   * The number in a migration's file name is the version it raises the store to, so the two must
   * not be able to drift apart. Every migration must also actually be in the built artifact.
   */
  @Test
  void theMigrationsAreNumberedFromOneWithoutGapsAndArePackaged() {
    List<String> resources = SchemaMigrations.resourceNames();

    assertEquals(SchemaMigrations.latestVersion(), resources.size());
    for (int i = 0; i < resources.size(); i++) {
      String resource = resources.get(i);
      String expectedPrefix = "db/migration/V%03d__".formatted(i + 1);
      assertTrue(
          resource.startsWith(expectedPrefix),
          () -> resource + " does not start with " + expectedPrefix);
      assertNotNull(
          getClass().getClassLoader().getResource(resource),
          () -> resource + " is not on the classpath");
    }
  }

  @Test
  void reopeningAnExistingStoreRunsNoMigrationAndKeepsItsContents() {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
      harness.restart();
    }

    assertEquals(
        SchemaMigrations.latestVersion(), userVersionOf(ServiceHarness.storeFileIn(directory)));
  }

  /**
   * A future SecondFactor is out of scope for v1, but the column is reserved now so that adding it
   * later is a migration about behaviour rather than about shape.
   */
  @Test
  void theAccountsTableReservesAColumnForAFutureSecondFactor() {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertTrue(
        columnsOf(ServiceHarness.storeFileIn(directory), "accounts").contains("second_factor"),
        "accounts has no reserved second_factor column");
  }

  /**
   * The band is recorded per Account. Nothing records the estimate it was made from: a store that
   * leaked with scores in it would name which Account is cheapest to attack.
   */
  @Test
  void theAccountsTableRecordsTheCoarseStrengthBand() {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertTrue(
        columnsOf(ServiceHarness.storeFileIn(directory), "accounts").contains("password_strength"),
        "accounts does not record a PasswordStrength band");
  }

  /**
   * The upgrade path, on a store that already holds an Account. A migration that only ever ran
   * against an empty file would be untested in the one case that matters — the installed one — and
   * an Account it dropped or refused to read is an Administrator locked out of their own product.
   */
  @Test
  void aStoreAtAnEarlierSchemaIsUpgradedWithItsAccountsIntact() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    createStoreAtTheInitialSchema(storeFile);

    try (AuthenticationService service =
        AuthenticationService.open(storeFile, ServiceHarness.CHEAP)) {
      assertEquals(SchemaMigrations.latestVersion(), userVersionOf(storeFile));
    }

    assertEquals("WEAK", strengthRecordedFor("wren.holloway", storeFile));
  }

  /**
   * Builds a store the way the first release of this schema did: the initial migration, its version
   * stamp, and an Account recorded before a band was ever estimated.
   */
  private static void createStoreAtTheInitialSchema(Path storeFile) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(read(SchemaMigrations.resourceNames().get(0)));
      statement.execute("PRAGMA user_version = 1");
      statement.executeUpdate(
          "INSERT INTO accounts (name, role, password_hash, created_at)"
              + " VALUES ('wren.holloway', 'ADMINISTRATOR', 'not-verified-here', '2026-01-01')");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String read(String resource) {
    try (InputStream stream =
        CredentialStoreSchemaTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(stream, () -> resource + " is not on the classpath");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String strengthRecordedFor(String accountName, Path storeFile) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        PreparedStatement statement =
            connection.prepareStatement("SELECT password_strength FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> "the Account named " + accountName + " did not survive");
        return results.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * There is no periodic password expiry, per current OWASP guidance. This guards the shape rather
   * than the behaviour: a rotation that nothing stores a due date for cannot be enforced.
   */
  @Test
  void nothingInTheSchemaExpiresAPassword() {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    List<String> columns = columnsOf(ServiceHarness.storeFileIn(directory), "accounts");

    assertTrue(
        columns.stream().noneMatch(column -> column.contains("expir")),
        () -> "something in " + columns + " expires a password");
  }

  /** A downgrade must fail loudly rather than write into a schema it does not understand. */
  @Test
  void theServiceRefusesToStartAgainstANewerSchemaThanItUnderstands() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }
    setUserVersion(storeFile, SchemaMigrations.latestVersion() + 1);

    SchemaTooNewException thrown =
        assertThrows(
            SchemaTooNewException.class,
            () -> AuthenticationService.open(storeFile, ServiceHarness.CHEAP));

    assertEquals(SchemaMigrations.latestVersion() + 1, thrown.foundVersion());
    assertEquals(SchemaMigrations.latestVersion(), thrown.understoodVersion());
  }

  @Test
  void aStoreAtTheUnderstoodVersionStillOpens() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    try (AuthenticationService reopened =
        AuthenticationService.open(storeFile, Argon2Parameters.PRODUCTION)) {
      assertEquals(SchemaMigrations.latestVersion(), userVersionOf(storeFile));
    }
  }

  private static int userVersionOf(Path storeFile) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("PRAGMA user_version")) {
      return results.next() ? results.getInt(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void setUserVersion(Path storeFile, int version) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = " + version);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> columnsOf(Path storeFile, String table) {
    List<String> columns = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (results.next()) {
        columns.add(results.getString("name"));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return columns;
  }
}
