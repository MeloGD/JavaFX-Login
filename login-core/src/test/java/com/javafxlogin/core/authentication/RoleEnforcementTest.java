package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the Role an attempt asks for is checked by the service, and a mismatch is refused in the
 * same words as a wrong password.
 *
 * <p>This is where the Administrator is kept out of the ProtectedFeature. The check lives here, in
 * the privileged process, rather than in the client that draws the window — a client is a thing an
 * attacker can patch, and the point of the exclusion is that patching it buys nothing.
 */
class RoleEnforcementTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

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

  @Test
  void anOperatorAskingToActAsAnOperatorIsGranted() {
    Response response = authenticate(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    assertInstanceOf(Granted.class, response);
  }

  @Test
  void anAdministratorAskingToActAsAnAdministratorIsGranted() {
    Response response = authenticate(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    assertInstanceOf(Granted.class, response);
  }

  /** Story 38: ordinary administration cannot touch the feature by habit or by accident. */
  @Test
  void theAdministratorIsRefusedTheRoleThatReachesTheProtectedFeature() {
    Response response = authenticate(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.OPERATOR);

    Denied denied = assertInstanceOf(Denied.class, response);
    assertEquals(DeniedReason.AUTH_FAILED, denied.reason());
  }

  @Test
  void anOperatorIsRefusedTheAdministratorsRole() {
    Response response = authenticate(OPERATOR, OPERATOR_PASSWORD, Role.ADMINISTRATOR);

    assertInstanceOf(Denied.class, response);
  }

  /**
   * The refusal must not be readable as "this name is the Administrator". Were it distinguishable
   * from a wrong password, the one Account whose Role an attacker can guess would be confirmed for
   * them by the login screen — and ADR-0002 keeps the account list unreadable precisely so that it
   * cannot be.
   */
  @Test
  void theRefusalIsIndistinguishableFromAWrongPassword() {
    Response forTheWrongRole = authenticate(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.OPERATOR);
    Response forAWrongPassword = authenticate(ADMINISTRATOR, "Wrong-Horse-9", Role.ADMINISTRATOR);
    Response forAnUnknownAccount = authenticate("nobody.here", OPERATOR_PASSWORD, Role.OPERATOR);

    assertEquals(forAWrongPassword, forTheWrongRole);
    assertEquals(forAnUnknownAccount, forTheWrongRole);
  }

  private Response authenticate(String accountName, String password, Role requestedRole) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), requestedRole));
  }
}
