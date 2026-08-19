package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AccountsListed;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.ListAccounts;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: what the administration panel is told about the Accounts it lists, and who may ask.
 *
 * <p>Story 18 and issue #12's first criterion. The list is the one place the CredentialStore is
 * read out as a whole, so what it carries is settled here rather than at the window that draws it:
 * a name, a Role, a coarse band, a language preference and whether the Account is locked out — and
 * no password material of any kind, because a hash that reaches an unprivileged process is the one
 * failure ADR-0002 exists to prevent.
 */
class AccountListingTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final String WRONG_PASSWORD = "Wrong-Horse-9";

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithBothRoles() {
    harness = ServiceHarness.cheap(directory);
    harness.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    harness.provisionOperator(OPERATOR, OPERATOR_PASSWORD);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  /** Every Account, in one order rather than in whichever order the store happens to return. */
  @Test
  void everyAccountIsListedByNameWithTheRoleItHolds() {
    List<AccountSummary> accounts = accountsSeenByTheAdministrator();

    assertEquals(
        List.of(OPERATOR, ADMINISTRATOR),
        accounts.stream().map(AccountSummary::name).toList(),
        "both Accounts should be listed, in the order a person reads them");
    assertEquals(Role.ADMINISTRATOR, summaryOf(accounts, ADMINISTRATOR).role());
    assertEquals(Role.OPERATOR, summaryOf(accounts, OPERATOR).role());
  }

  /** The band and never the score: what the store holds is what the panel shows. */
  @Test
  void everyAccountIsListedWithTheCoarseBandOfItsPassword() {
    List<AccountSummary> accounts = accountsSeenByTheAdministrator();

    assertEquals(
        PasswordStrength.ACCEPTABLE, summaryOf(accounts, OPERATOR).passwordStrength());
  }

  /**
   * An Account nobody has enrolled against holds no password, and the band of a password nobody has
   * chosen must not read as a strong one.
   */
  @Test
  void anAccountAwaitingEnrolmentIsListedAtTheWeakestBand() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new CreateAccount(administrator, "juno.vale", Role.OPERATOR));

    List<AccountSummary> accounts = accountsOf(harness.send(new ListAccounts(administrator)));

    assertEquals(PasswordStrength.WEAK, summaryOf(accounts, "juno.vale").passwordStrength());
  }

  /** Nobody has said which language they read, so nothing is claimed about one. */
  @Test
  void anAccountThatHasNotChosenALanguageIsListedWithoutOne() {
    List<AccountSummary> accounts = accountsSeenByTheAdministrator();

    assertEquals(Optional.empty(), summaryOf(accounts, OPERATOR).language());
  }

  /** Criterion 5's other half: an Administrator can only clear what they can see. */
  @Test
  void anAccountThatIsLockedOutIsListedAsLockedAndSaysForHowLong() {
    harness.lockoutPolicyIs(2, Duration.ofMinutes(10));
    failTwiceAgainst(OPERATOR);

    List<AccountSummary> accounts = accountsSeenByTheAdministrator();

    assertEquals(
        Optional.of(Duration.ofMinutes(10)), summaryOf(accounts, OPERATOR).lockedFor());
  }

  @Test
  void anAccountThatIsNotLockedOutIsListedAsNotLocked() {
    List<AccountSummary> accounts = accountsSeenByTheAdministrator();

    assertEquals(Optional.empty(), summaryOf(accounts, OPERATOR).lockedFor());
  }

  /**
   * The list is refused to an Operator by the service, which is what makes the panel unreachable
   * rather than merely unshown: a patched client that drew it anyway would be drawing an empty one.
   */
  @Test
  void anOperatorIsRefusedTheListOfAccounts() {
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    Response response = harness.send(new ListAccounts(operator));

    assertEquals(ErrorCode.NOT_ADMINISTRATOR, assertInstanceOf(ErrorResponse.class, response).code());
  }

  @Test
  void aSessionThatIsOverIsToldSoRatherThanAnsweredWithTheList() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new Logout(administrator));

    assertInstanceOf(SessionEnded.class, harness.send(new ListAccounts(administrator)));
  }

  /** ADR-0002: nothing of the password itself leaves the privileged process, not even by accident. */
  @Test
  void nothingInTheListCarriesTheHashTheStoreHolds() {
    String hash = hashHeldFor(OPERATOR);

    String listed = accountsSeenByTheAdministrator().toString();

    assertTrue(listed.contains(OPERATOR), () -> "the Operator should be in " + listed);
    assertFalse(listed.contains(hash), () -> "a hash reached the list: " + listed);
  }

  /** Read straight out of the store, which is the only place a hash is allowed to be. */
  private String hashHeldFor(String accountName) {
    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + ServiceHarness.storeFileIn(directory));
        PreparedStatement statement =
            connection.prepareStatement("SELECT password_hash FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> "there is no Account named " + accountName);
        return results.getString("password_hash");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<AccountSummary> accountsSeenByTheAdministrator() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    return accountsOf(harness.send(new ListAccounts(administrator)));
  }

  private static List<AccountSummary> accountsOf(Response response) {
    return assertInstanceOf(AccountsListed.class, response).accounts();
  }

  private static AccountSummary summaryOf(List<AccountSummary> accounts, String name) {
    return accounts.stream()
        .filter(account -> account.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no Account named " + name + " in " + accounts));
  }

  private void failTwiceAgainst(String accountName) {
    for (int attempt = 0; attempt < 2; attempt++) {
      assertInstanceOf(
          Denied.class,
          harness.send(
              new Authenticate(accountName, WRONG_PASSWORD.toCharArray(), Role.OPERATOR)));
    }
  }

  private SessionToken admit(String accountName, String password, Role role) {
    Response response = harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response).token();
  }
}
