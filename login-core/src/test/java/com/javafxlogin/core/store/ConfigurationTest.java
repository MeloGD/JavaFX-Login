package com.javafxlogin.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.javafxlogin.core.session.InactivityPeriod;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The configuration of the application, which lives in the same file as the Accounts. */
class ConfigurationTest {

  @TempDir Path directory;

  /** The value V003 writes and the constant the code names must be the same fifteen minutes. */
  @Test
  void aFreshStoreExpiresSessionsAfterTheDefaultPeriod() {
    try (CredentialStore store = openStore()) {
      assertEquals(InactivityPeriod.DEFAULT, store.inactivityPeriod());
    }
  }

  @Test
  void remembersWhatWasConfigured() {
    try (CredentialStore store = openStore()) {
      store.setInactivityPeriod(InactivityPeriod.of(Duration.ofMinutes(45)));

      assertEquals(InactivityPeriod.of(Duration.ofMinutes(45)), store.inactivityPeriod());
    }
  }

  @Test
  void remembersThatExpiryWasSwitchedOff() {
    try (CredentialStore store = openStore()) {
      store.setInactivityPeriod(InactivityPeriod.disabled());

      assertEquals(InactivityPeriod.disabled(), store.inactivityPeriod());
    }
  }

  @Test
  void whatWasConfiguredOutlivesTheProcessThatConfiguredIt() {
    try (CredentialStore store = openStore()) {
      store.setInactivityPeriod(InactivityPeriod.of(Duration.ofMinutes(45)));
    }

    try (CredentialStore reopened = openStore()) {
      assertEquals(InactivityPeriod.of(Duration.ofMinutes(45)), reopened.inactivityPeriod());
    }
  }

  /** A store somebody edited by hand is refused rather than guessed at. */
  @Test
  void aSettingThatIsNotAPeriodIsNotGuessedAt() {
    try (CredentialStore store = openStore()) {
      overwriteTheConfiguredPeriodWith("half an hour");

      assertThrows(CredentialStoreException.class, store::inactivityPeriod);
    }
  }

  @Test
  void aSettingThatIsMissingIsNotGuessedAtEither() {
    try (CredentialStore store = openStore()) {
      execute("DELETE FROM configuration");

      assertThrows(CredentialStoreException.class, store::inactivityPeriod);
    }
  }

  private CredentialStore openStore() {
    return CredentialStore.openOrCreate(directory.resolve("credentials.db"));
  }

  private void overwriteTheConfiguredPeriodWith(String value) {
    execute("UPDATE configuration SET value = '" + value + "'");
  }

  private void execute(String sql) {
    String file = directory.resolve("credentials.db").toString();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
