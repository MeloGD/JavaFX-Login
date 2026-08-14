package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.authentication.ServiceProcess;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.store.CredentialStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gate a shipped product runs behind, against a real AuthenticationService over a real socket.
 *
 * <p>Seam 3 fakes this gate so that the windows can be tested without any of it. This is the one
 * place where the fake is checked against the thing it stands in for: that a Session really is
 * granted, that a refusal really comes back as a refusal, and — the part that matters most — that
 * the Administrator is turned away by the service rather than by anything in this process.
 *
 * <p>No JavaFX is involved and no display is needed. The gate's other half, which shows windows, is
 * {@link LoginWindowTest}'s subject.
 */
class ServiceLoginGateTest {

  /** Deliberately cheap: this suite is about who is admitted, not about what a hash costs. */
  private static final Argon2Parameters CHEAP = new Argon2Parameters(256, 1, 1, 32);

  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";

  @TempDir Path runtimeDirectory;

  private Path socketPath;
  private Path storeFile;
  private ServiceProcess process;

  @BeforeEach
  void startTheService() throws IOException {
    socketPath = runtimeDirectory.resolve("authentication.sock");
    storeFile = runtimeDirectory.resolve("credentials.db");
    provisionOperator();
    process = start();
  }

  @AfterEach
  void stopTheService() {
    process.close();
  }

  @Test
  void admitsAnOperatorWithTheirOwnPassword() {
    Optional<Session> session = gate().admit(OPERATOR, OPERATOR_PASSWORD.toCharArray());

    assertTrue(session.isPresent(), "an Operator should have been admitted");
    assertEquals(16, session.orElseThrow().token().copyOfBytes().length);
  }

  @Test
  void refusesAWrongPassword() {
    Optional<Session> session = gate().admit(OPERATOR, "Wrong-Horse-9".toCharArray());

    assertTrue(session.isEmpty(), "a wrong password should have been refused");
  }

  @Test
  void refusesAnAccountThatDoesNotExist() {
    Optional<Session> session = gate().admit("nobody.here", OPERATOR_PASSWORD.toCharArray());

    assertTrue(session.isEmpty(), "an unknown Account should have been refused");
  }

  /**
   * Stories 38 and 39: the Administrator does not reach the ProtectedFeature, and it is the service
   * that says so. This gate asks to act as an Operator and is told no with the Administrator's own
   * correct password — a client patched to ignore the answer would still hold no Session.
   */
  @Test
  void refusesTheAdministratorEvenWithTheRightPassword() throws IOException {
    createTheAdministrator();

    Optional<Session> session = gate().admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray());

    assertTrue(session.isEmpty(), "the Administrator should not reach the ProtectedFeature");
  }

  /** Not being able to ask is not a refusal, and the person must not be sent to retype anything. */
  @Test
  void saysSoWhenThereIsNoServiceToAsk() {
    LoginGate gate = LoginGate.toService(runtimeDirectory.resolve("nothing-listens-here.sock"));

    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));
  }

  /**
   * A Session is bound to its connection, so the gate keeps one. When the service goes away that
   * connection dies with it: the attempt in flight fails as unreachable, and the next one opens a
   * fresh connection rather than the gate being useless until the application restarts.
   */
  @Test
  void reconnectsAfterTheServiceHasBeenRestarted() throws IOException {
    LoginGate gate = gate();
    assertTrue(gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()).isPresent());

    process.close();
    process = start();

    assertThrows(
        ServiceUnreachableException.class,
        () -> gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()));
    assertTrue(
        gate.admit(OPERATOR, OPERATOR_PASSWORD.toCharArray()).isPresent(),
        "the next attempt should have connected again");
  }

  private LoginGate gate() {
    return LoginGate.toService(socketPath);
  }

  private ServiceProcess start() throws IOException {
    return ServiceProcess.start(new BoundListeningChannelSource(socketPath), storeFile, CHEAP);
  }

  /** Nothing creates an Operator yet — enrolment is its own ticket — so the store is written to. */
  private void provisionOperator() {
    String hash = new Authenticator(CHEAP).hash(OPERATOR_PASSWORD.toCharArray());
    try (CredentialStore store = CredentialStore.openOrCreate(storeFile)) {
      store.insert(new Account(OPERATOR, Role.OPERATOR, hash, PasswordStrength.ACCEPTABLE));
    }
  }

  /** Over the wire, as the first-run wizard will: this test has no privileges the client lacks. */
  private void createTheAdministrator() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new Bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD.toCharArray()));
    }
  }
}
