package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Assess;
import com.javafxlogin.core.ipc.Assessed;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.policy.PolicyViolation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the password and naming rules as the service enforces them.
 *
 * <p>They are tested here rather than only as a unit because enforcing them inside the service is
 * the point: a patched client that never checks a password still cannot create a weak Account.
 */
class PolicyEnforcementTest {

  private static final String NAME = "wren.holloway";
  private static final String PASSWORD = "Correct-Horse-1";

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openService() {
    harness = ServiceHarness.cheap(directory);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void bootstrapIsRefusedWhenThePasswordBreaksTheRules() {
    Response response = harness.bootstrap(NAME, "short");

    PolicyRefused refused = assertInstanceOf(PolicyRefused.class, response);
    assertTrue(refused.violations().contains(PolicyViolation.PASSWORD_TOO_SHORT));
  }

  @Test
  void bootstrapIsRefusedWhenTheAdministratorNameIsPredictable() {
    Response response = harness.bootstrap("Adm1n", PASSWORD);

    PolicyRefused refused = assertInstanceOf(PolicyRefused.class, response);
    assertEquals(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED), refused.violations());
  }

  /** Every refusal carries its reasons, so the wizard can explain the rule rather than guess it. */
  @Test
  void aRefusalCarriesEveryReasonAtOnce() {
    Response response = harness.bootstrap("root", "short");

    PolicyRefused refused = assertInstanceOf(PolicyRefused.class, response);
    assertTrue(
        refused
            .violations()
            .containsAll(
                List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED, PolicyViolation.PASSWORD_TOO_SHORT)),
        () -> "only reported " + refused.violations());
  }

  @Test
  void aRefusedBootstrapCreatesNoAdministrator() {
    harness.bootstrap("root", "short");

    assertInstanceOf(Ok.class, harness.bootstrap(NAME, PASSWORD));
  }

  @Test
  void anAccountThatBreaksNoRuleIsCreated() {
    assertInstanceOf(Ok.class, harness.bootstrap(NAME, PASSWORD));
    assertInstanceOf(
        Granted.class,
        harness.send(new Authenticate(NAME, PASSWORD.toCharArray(), Role.ADMINISTRATOR)));
  }

  /** The wizard asks before it submits, so that the rules it shows are the rules that apply. */
  @Test
  void assessReportsTheViolationsWithoutCreatingAnything() {
    Response response = harness.send(new Assess("root", "short".toCharArray()));

    Assessed assessed = assertInstanceOf(Assessed.class, response);
    assertFalse(assessed.assessment().violations().isEmpty());
    assertInstanceOf(
        Denied.class,
        harness.send(new Authenticate("root", "short".toCharArray(), Role.ADMINISTRATOR)));
  }

  @Test
  void assessReturnsAStrengthEstimateForDisplay() {
    Assessed assessed = (Assessed) harness.send(new Assess(NAME, PASSWORD.toCharArray()));

    assertEquals(List.of(), assessed.assessment().violations());
    assertTrue(
        EnumSet.allOf(PasswordStrength.class).contains(assessed.assessment().strength()),
        () -> "no band to display: " + assessed.assessment().strength());
  }

  /**
   * Only the band is recorded. A precise score in a leaked store would rank the Accounts by how
   * cheap each one is to attack, which is a shopping list.
   */
  @Test
  void onlyTheCoarseStrengthBandIsStored() {
    harness.bootstrap(NAME, PASSWORD);

    String stored = storedStrengthOf(NAME);

    assertTrue(
        EnumSet.allOf(PasswordStrength.class).contains(PasswordStrength.valueOf(stored)),
        () -> "not a coarse band: " + stored);
  }

  /** The deployment file sits beside the store, which only the service can write. */
  @Test
  void aDeploymentBlocklistBesideTheStoreIsEnforced() throws IOException {
    Files.writeString(directory.resolve("blocked-account-names.txt"), "Hollow Reach\n");
    harness.restart();

    Response response = harness.bootstrap("hollow.reach", PASSWORD);

    PolicyRefused refused = assertInstanceOf(PolicyRefused.class, response);
    assertEquals(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED), refused.violations());
  }

  private String storedStrengthOf(String accountName) {
    String storeFile = ServiceHarness.storeFileIn(directory).toString();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        PreparedStatement statement =
            connection.prepareStatement("SELECT password_strength FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> "there was no Account named " + accountName);
        return results.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
