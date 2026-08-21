package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.store.SchemaMigrations;
import com.javafxlogin.core.store.SchemaTooNewException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The upgrade the package runs before anybody logs in.
 *
 * <p>Migrations already run when the AuthenticationService opens its files, so what is at stake
 * here is <em>when</em> they run. Left to the first activation, a migration that fails is a login
 * screen saying the service is not running, on a machine whose owner has just been told the
 * installation succeeded — and under socket activation those two look identical from the outside.
 * Run from the package's {@code postinst}, the same failure stops the install and names the schema
 * version it found.
 */
class UpgradeBringsTheFilesForwardTest {

  /** The initial migration, read as the first release of this schema wrote it. */
  private static final String INITIAL_SCHEMA = "db/migration/V001__initial_schema.sql";

  @TempDir Path directory;

  @Test
  void aStoreFromAnOlderBuildIsBroughtToTheVersionThisBuildUnderstands() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    createStoreAtTheInitialSchema(storeFile);

    int version = ServiceProcess.bringTheFilesUpToDate(storeFile);

    assertEquals(SchemaMigrations.latestVersion(), version);
    assertEquals(SchemaMigrations.latestVersion(), userVersionOf(storeFile));
  }

  @Test
  void aStoreAlreadyAtThisVersionIsLeftWhereItIs() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertEquals(SchemaMigrations.latestVersion(), ServiceProcess.bringTheFilesUpToDate(storeFile));
  }

  /**
   * A downgrade stops the installation rather than the next login. The numbers travel with the
   * refusal because the remedy is to put the build that wrote the file back, and nothing else on
   * the machine says which build that was.
   */
  @Test
  void aStoreNewerThanThisBuildStopsTheUpgradeAndNamesBothVersions() {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }
    setUserVersion(storeFile, SchemaMigrations.latestVersion() + 1);

    SchemaTooNewException thrown =
        assertThrows(
            SchemaTooNewException.class, () -> ServiceProcess.bringTheFilesUpToDate(storeFile));

    assertEquals(SchemaMigrations.latestVersion() + 1, thrown.foundVersion());
    assertEquals(SchemaMigrations.latestVersion(), thrown.understoodVersion());
  }

  /**
   * A first installation has nothing to migrate, and the upgrade must not be what creates the
   * deployment. A CredentialStore, a SecretVault and an event log written by {@code postinst} would
   * be a machine that has been installed on rather than one nobody has logged in to yet, and the
   * FirstRunWizard is the only thing that may make the difference.
   */
  @Test
  void afterAFirstInstallationThereIsNothingToBringForwardAndNothingIsCreated() {
    Path storeFile = ServiceHarness.storeFileIn(directory);

    assertEquals(0, ServiceProcess.bringTheFilesUpToDate(storeFile));

    try (Stream<Path> written = Files.list(directory)) {
      assertTrue(written.findAny().isEmpty(), "the upgrade created a deployment out of nothing");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Builds a store the way the first release of this schema did: the initial migration, its version
   * stamp, and an Account recorded before anything later existed.
   */
  private static void createStoreAtTheInitialSchema(Path storeFile) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(read());
      statement.execute("PRAGMA user_version = 1");
      statement.executeUpdate(
          "INSERT INTO accounts (name, role, password_hash, created_at)"
              + " VALUES ('wren.holloway', 'ADMINISTRATOR', 'not-verified-here', '2026-01-01')");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String read() {
    try (InputStream stream =
        UpgradeBringsTheFilesForwardTest.class
            .getClassLoader()
            .getResourceAsStream(INITIAL_SCHEMA)) {
      if (stream == null) {
        throw new IllegalStateException(INITIAL_SCHEMA + " is not on the classpath");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
}
