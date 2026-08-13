package com.javafxlogin.core.store;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.daemon.AuthenticationService;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CredentialStore's schema: versioned, migrated by number, and refused when it is too new. */
class CredentialStoreSchemaTest {

    @TempDir
    Path directory;

    @Test
    void aFreshStoreIsMigratedToTheLatestVersion() {
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }

        assertEquals(SchemaMigrations.latestVersion(), userVersionOf(directory.resolve("credentials.db")));
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
            assertTrue(resource.startsWith(expectedPrefix),
                    () -> resource + " does not start with " + expectedPrefix);
            assertNotNull(getClass().getClassLoader().getResource(resource),
                    () -> resource + " is not on the classpath");
        }
    }

    @Test
    void reopeningAnExistingStoreRunsNoMigrationAndKeepsItsContents() {
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
            harness.restart();
        }

        assertEquals(SchemaMigrations.latestVersion(), userVersionOf(directory.resolve("credentials.db")));
    }

    /**
     * A future SecondFactor is out of scope for v1, but the column is reserved now so that adding it
     * later is a migration about behaviour rather than about shape.
     */
    @Test
    void theAccountsTableReservesAColumnForAFutureSecondFactor() {
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }

        assertTrue(columnsOf(directory.resolve("credentials.db"), "accounts").contains("second_factor"),
                "accounts has no reserved second_factor column");
    }

    /** A downgrade must fail loudly rather than write into a schema it does not understand. */
    @Test
    void theServiceRefusesToStartAgainstANewerSchemaThanItUnderstands() {
        Path storeFile = directory.resolve("credentials.db");
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }
        setUserVersion(storeFile, SchemaMigrations.latestVersion() + 1);

        SchemaTooNewException thrown = assertThrows(SchemaTooNewException.class,
                () -> AuthenticationService.open(storeFile, ServiceHarness.CHEAP));

        assertEquals(SchemaMigrations.latestVersion() + 1, thrown.foundVersion());
        assertEquals(SchemaMigrations.latestVersion(), thrown.understoodVersion());
    }

    @Test
    void aStoreAtTheUnderstoodVersionStillOpens() {
        Path storeFile = directory.resolve("credentials.db");
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }

        try (AuthenticationService reopened = AuthenticationService.open(storeFile, Argon2Parameters.PRODUCTION)) {
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
