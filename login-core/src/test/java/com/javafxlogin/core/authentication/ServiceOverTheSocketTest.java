package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.ipc.TransportClient;
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

  @TempDir Path runtimeDirectory;

  private Path socketPath;
  private ServiceProcess process;

  @BeforeEach
  void startTheService() throws IOException {
    socketPath = runtimeDirectory.resolve("authentication.sock");
    ServiceHarness.provisionOperatorIn(
        runtimeDirectory, ServiceHarness.CHEAP, OPERATOR, OPERATOR_PASSWORD);
    process =
        ServiceProcess.start(
            new BoundListeningChannelSource(socketPath),
            ServiceHarness.storeFileIn(runtimeDirectory),
            ServiceHarness.CHEAP);
  }

  @AfterEach
  void stopTheService() {
    process.close();
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
