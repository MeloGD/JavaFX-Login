package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.AskIfSessionIsLive;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Reachable;
import com.javafxlogin.core.ipc.ReportActivity;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.ipc.ServiceHandshake;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.ipc.SessionLive;
import com.javafxlogin.core.ipc.TransportClient;
import com.javafxlogin.core.machine.MachineAdministrators;
import com.javafxlogin.core.session.SessionEndedReason;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seams 1 and 2 joined: the real service, behind the real socket, answering a real client.
 *
 * <p>The two seams are tested apart on purpose, and this is the small set of tests that proves the
 * join itself — that what the service decides is what the client is told, across the wire, in both
 * directions. Everything about <em>why</em> a request is answered the way it is belongs to Seam 1,
 * where no socket is in the way.
 */
class ServiceOverTheSocketTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  /**
   * The machine's answer, decided here rather than read from the developer's group memberships —
   * and decided on the name the kernel attached to the connection, so that what these tests admit
   * is exactly what {@code SO_PEERCRED} reported about this very process.
   */
  private static final MachineAdministrators THE_ACCOUNT_RUNNING_THIS_SUITE =
      peer -> System.getProperty("user.name").equals(peer.userName());

  private static final MachineAdministrators NOBODY = peer -> false;

  @TempDir Path runtimeDirectory;

  private Path socketPath;
  private ServiceProcess process;

  @BeforeEach
  void startTheService() throws IOException {
    socketPath = runtimeDirectory.resolve("authentication.sock");
    ServiceHarness.provisionOperatorIn(
        runtimeDirectory, ServiceHarness.CHEAP, OPERATOR, OPERATOR_PASSWORD);
    process = start(THE_ACCOUNT_RUNNING_THIS_SUITE);
  }

  private ServiceProcess start(MachineAdministrators administrators) throws IOException {
    return ServiceProcess.start(
        new BoundListeningChannelSource(socketPath),
        ServiceHarness.storeFileIn(runtimeDirectory),
        ServiceHarness.CHEAP,
        administrators);
  }

  @AfterEach
  void stopTheService() {
    process.close();
  }

  /**
   * Issue #16's join: the reachability a client works out before it draws anything, worked out
   * against the real service over the real socket rather than against a stub that agrees with it.
   * Seam 2 tells the three failures apart; this is the one case that has to be right about a
   * service that is genuinely there.
   */
  @Test
  void findsTheRealServiceReachableAndSpeakingThisProtocol() {
    assertInstanceOf(Reachable.class, ServiceHandshake.attemptedAt(socketPath));
  }

  /**
   * Asking costs nothing that is later needed. The handshake takes a connection of its own and
   * gives it back, so it must not have spent the one Session story 54 allows this machine — an
   * application that refused to admit anybody because it had checked whether it could would be a
   * gate that locked itself.
   */
  @Test
  void askingDoesNotSpendTheOneSessionThisMachineAllows() throws IOException {
    assertInstanceOf(Reachable.class, ServiceHandshake.attemptedAt(socketPath));

    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      assertInstanceOf(
          Granted.class, authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));
    }
  }

  @Test
  void createsTheAdministratorOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response response = client.send(new Bootstrap(ADMINISTRATOR, chars(ADMINISTRATOR_PASSWORD)));

      assertInstanceOf(Ok.class, response);
    }
  }

  @Test
  void refusesASecondAdministratorOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new Bootstrap(ADMINISTRATOR, chars(ADMINISTRATOR_PASSWORD)));

      Response response = client.send(new Bootstrap("someone.else", chars("Another-Horse-3")));

      ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
      assertEquals(ErrorCode.ADMINISTRATOR_EXISTS, error.code());
    }
  }

  /**
   * The join this ticket adds: the name the kernel attached to the socket reaches the service, and
   * a peer it does not admit is refused across the wire. Nothing the client sent took part in it —
   * the same request was accepted a test ago, over the same socket, by the same code.
   */
  @Test
  void refusesToCreateTheAdministratorForAPeerTheMachineDoesNotAdminister() throws IOException {
    process.close();
    process = start(NOBODY);

    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response response = client.send(new Bootstrap(ADMINISTRATOR, chars(ADMINISTRATOR_PASSWORD)));

      ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
      assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
    }
  }

  @Test
  void tellsAClientWhetherTheFirstRunWizardIsNeededOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      assertTrue(
          assertInstanceOf(BootstrapNeeded.class, client.send(new AskIfBootstrapNeeded()))
              .needed());

      client.send(new Bootstrap(ADMINISTRATOR, chars(ADMINISTRATOR_PASSWORD)));

      assertFalse(
          assertInstanceOf(BootstrapNeeded.class, client.send(new AskIfBootstrapNeeded()))
              .needed());
    }
  }

  /** The walking skeleton's own path: an Operator asks to act as one, and is granted a Session. */
  @Test
  void grantsAnOperatorASessionTokenOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response response = authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

      Granted granted = assertInstanceOf(Granted.class, response);
      assertEquals(16, granted.token().copyOfBytes().length);
    }
  }

  @Test
  void deniesAWrongPasswordOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Response response = authenticate(client, OPERATOR, "Wrong-Horse-9", Role.OPERATOR);

      assertInstanceOf(Denied.class, response);
    }
  }

  /**
   * The refusal that keeps the Administrator out of the ProtectedFeature is made in the privileged
   * process and arrives over the wire. A client is welcome to ask; it is not the one answering.
   */
  @Test
  void deniesTheAdministratorTheOperatorsRoleOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new Bootstrap(ADMINISTRATOR, chars(ADMINISTRATOR_PASSWORD)));

      Response asAnOperator =
          authenticate(client, ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.OPERATOR);
      Response asAnAdministrator =
          authenticate(client, ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

      assertInstanceOf(Denied.class, asAnOperator);
      assertInstanceOf(Granted.class, asAnAdministrator);
    }
  }

  /** One connection, several requests: a Session lasts as long as the connection carrying it. */
  @Test
  void answersSeveralRequestsOnOneConnection() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      assertInstanceOf(
          Denied.class, authenticate(client, OPERATOR, "Wrong-Horse-9", Role.OPERATOR));
      assertInstanceOf(
          Granted.class, authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));

      assertTrue(client.isOpen(), "the connection should still carry the Session");
    }
  }

  /** The whole of a Session, over the wire: activity reported, time left read, and a logout. */
  @Test
  void carriesEveryPartOfASessionOverTheSocket() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      Granted granted =
          (Granted) authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

      assertInstanceOf(SessionLive.class, client.send(new ReportActivity(granted.token())));
      assertInstanceOf(SessionLive.class, client.send(new AskIfSessionIsLive(granted.token())));
      assertInstanceOf(Ok.class, client.send(new Logout(granted.token())));
      assertEquals(
          new SessionEnded(SessionEndedReason.NO_SUCH_SESSION),
          client.send(new AskIfSessionIsLive(granted.token())));
    }
  }

  /** Story 54, across two real connections: the machine holds one Session and keeps the first. */
  @Test
  void refusesASecondSessionOverTheSocket() throws IOException {
    try (ServiceClient first = ServiceClient.connect(socketPath);
        ServiceClient second = ServiceClient.connect(socketPath)) {
      Granted granted = (Granted) authenticate(first, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

      Response refused = authenticate(second, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

      assertEquals(Denied.because(DeniedReason.SESSION_ALREADY_LIVE), refused);
      assertInstanceOf(SessionLive.class, first.send(new AskIfSessionIsLive(granted.token())));
    }
  }

  /**
   * Story 50, with a real socket doing the noticing: the client goes, the kernel closes the
   * connection, and the Session goes with it. Nothing here sends a heartbeat or waits for one — the
   * only thing this test waits for is the operating system telling the service what happened.
   */
  @Test
  void endsTheSessionOfAClientThatDisappears() throws IOException, InterruptedException {
    ServiceClient crashing = ServiceClient.connect(socketPath);
    assertInstanceOf(
        Granted.class, authenticate(crashing, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));

    crashing.close();

    assertTrue(theMachineBecomesFree(), "the Session outlived the client that owned it");
  }

  private boolean theMachineBecomesFree() throws IOException, InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      try (ServiceClient client = ServiceClient.connect(socketPath)) {
        if (authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR) instanceof Granted) {
          return true;
        }
      }
      Thread.sleep(50);
    }
    return false;
  }

  /**
   * ADR-0003's rule, one layer up: a payload that is not a message is not answered, guessed at or
   * resynchronised. The connection goes, and the service keeps serving everyone else.
   */
  @Test
  void dropsTheConnectionOfAPeerSendingSomethingThatIsNotAMessage() throws IOException {
    byte[] notAMessage = "not a message".getBytes(StandardCharsets.UTF_8);
    try (TransportClient rogue = TransportClient.connect(socketPath)) {
      assertThrows(EOFException.class, () -> rogue.request(notAMessage));
    }

    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      assertInstanceOf(
          Granted.class, authenticate(client, OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));
    }
  }

  private static Response authenticate(
      ServiceClient client, String accountName, String password, Role requestedRole)
      throws IOException {
    return client.send(new Authenticate(accountName, chars(password), requestedRole));
  }

  private static char[] chars(String text) {
    return text.toCharArray();
  }
}
