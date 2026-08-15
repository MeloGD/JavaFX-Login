package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Response;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Seam 1: what Authenticate grants, what it denies, and how little the denial says. */
class AuthenticationTest {

  private static final String NAME = "wren.holloway";
  private static final String PASSWORD = "Correct-Horse-1";

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithAnAdministrator() {
    harness = ServiceHarness.cheap(directory);
    harness.send(new Bootstrap(NAME, PASSWORD.toCharArray()));
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void aCorrectPasswordIsGranted() {
    Response response = authenticate(NAME, PASSWORD);

    assertInstanceOf(Granted.class, response);
  }

  @Test
  void theGrantedTokenIs128Bits() {
    Granted granted = (Granted) authenticate(NAME, PASSWORD);

    assertEquals(16, granted.token().copyOfBytes().length);
  }

  /**
   * The logout is what makes the second attempt possible at all: the machine holds one Session, and
   * a second authentication while one is live is refused. Ending it is how a suite gets to ask this
   * question, and what happens when it does not is {@link SessionLifecycleTest}'s subject.
   */
  @Test
  void everyAuthenticationIssuesAFreshToken() {
    Granted first = (Granted) authenticate(NAME, PASSWORD);
    harness.send(new Logout(first.token()));
    Granted second = (Granted) authenticate(NAME, PASSWORD);

    assertFalse(Arrays.equals(first.token().copyOfBytes(), second.token().copyOfBytes()));
  }

  /** Never logged: the object a caller would actually print must not carry the token into a log. */
  @Test
  void printingAGrantedDoesNotPrintItsToken() {
    Granted granted = (Granted) authenticate(NAME, PASSWORD);

    String printed = granted.toString();

    assertTrue(printed.contains("redacted"), () -> "not redacted: " + printed);
    assertFalse(
        printed.contains(HexFormat.of().formatHex(granted.token().copyOfBytes())),
        () -> "the token leaked into " + printed);
  }

  /** Every request is answered — a broken store becomes an Error, not an exception. */
  @Test
  void aStoreThatCannotBeReadIsAnsweredRatherThanThrown() {
    harness.close();

    Response response = authenticate(NAME, PASSWORD);

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.STORE_UNAVAILABLE, error.code());
  }

  /**
   * A stored hash that cannot be read is still a refusal, and the same refusal as any other. Were
   * it to escape as an exception, or to answer with an Error, a real Account with a damaged hash
   * would be distinguishable from one that does not exist — which is precisely what the denial is
   * not allowed to reveal.
   */
  @Test
  void anAccountWhoseStoredHashCannotBeReadIsDeniedLikeAnyOther() {
    overwriteStoredHashOf(NAME, "not-a-phc-string");

    Response forDamagedAccount = authenticate(NAME, PASSWORD);
    Response forAbsentAccount = authenticate("nobody.here", PASSWORD);

    assertInstanceOf(Denied.class, forDamagedAccount);
    assertEquals(forAbsentAccount, forDamagedAccount);
  }

  private void overwriteStoredHashOf(String accountName, String hash) {
    String storeFile = ServiceHarness.storeFileIn(directory).toString();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        PreparedStatement statement =
            connection.prepareStatement("UPDATE accounts SET password_hash = ? WHERE name = ?")) {
      statement.setString(1, hash);
      statement.setString(2, accountName);
      assertEquals(
          1,
          statement.executeUpdate(),
          () -> "there was no Account named " + accountName + " to damage");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aWrongPasswordIsDenied() {
    Response response = authenticate(NAME, "Wrong-Horse-9");

    Denied denied = assertInstanceOf(Denied.class, response);
    assertEquals(DeniedReason.AUTH_FAILED, denied.reason());
  }

  @Test
  void anUnknownAccountIsDenied() {
    Response response = authenticate("nobody.here", PASSWORD);

    assertInstanceOf(Denied.class, response);
  }

  /**
   * The refusal must not distinguish "no such Account" from "wrong password", or the login screen
   * becomes an oracle for the account list — which ADR-0002 exists to keep secret.
   */
  @Test
  void theDenialRevealsNothingAboutWhetherTheAccountExists() {
    Response forWrongPassword = authenticate(NAME, "Wrong-Horse-9");
    Response forUnknownAccount = authenticate("nobody.here", PASSWORD);

    assertEquals(forWrongPassword, forUnknownAccount);
  }

  @Test
  void anAccountNameIsMatchedExactly() {
    Response response = authenticate(NAME.toUpperCase(), PASSWORD);

    assertInstanceOf(Denied.class, response);
  }

  /**
   * The only Account this class provisions is the single Administrator, so every attempt here asks
   * to act in that Role. What happens when the Role asked for is not the one held is {@link
   * RoleEnforcementTest}'s subject.
   */
  private Response authenticate(String accountName, String password) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), Role.ADMINISTRATOR));
  }
}
