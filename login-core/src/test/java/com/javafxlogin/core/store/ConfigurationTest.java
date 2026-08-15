package com.javafxlogin.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.javafxlogin.core.account.LockoutPolicy;
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

  /** The values V004 writes, and the numbers this project says a deployment gets. */
  @Test
  void aFreshStoreLocksAnAccountOutAfterFiveFailuresForAQuarterOfAnHour() {
    try (CredentialStore store = openStore()) {
      assertEquals(new LockoutPolicy(5, Duration.ofMinutes(15)), store.lockoutPolicy());
    }
  }

  /** A store somebody edited by hand is refused rather than read as "never locks anyone out". */
  @Test
  void aLockoutPolicyThatIsNotOneIsNotGuessedAt() {
    try (CredentialStore store = openStore()) {
      execute("UPDATE configuration SET value = 'a few' WHERE name = 'lockout.failures_that_lock'");

      assertThrows(CredentialStoreException.class, store::lockoutPolicy);
    }
  }

  @Test
  void aLockoutThatLastsNoTimeIsNotAPolicyEither() {
    try (CredentialStore store = openStore()) {
      execute("UPDATE configuration SET value = 'PT0S' WHERE name = 'lockout.lasts_for'");

      assertThrows(CredentialStoreException.class, store::lockoutPolicy);
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
