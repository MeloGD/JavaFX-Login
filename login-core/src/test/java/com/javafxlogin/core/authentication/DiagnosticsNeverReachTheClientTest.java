package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.ipc.TransportClient;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The privileged service says nothing on standard output, ever.
 *
 * <p>Under systemd the service is handed its listening socket on file descriptor 0, and the trap
 * that comes with it is that {@code StandardOutput=} left at its default inherits that socket —
 * so anything printed would be written into whatever client happens to be connected, in the middle
 * of a framed protocol. The {@code .service} unit sets both streams to the journal and
 * {@code SystemdUnitFilesTest} holds it to that; this is the other half, and the half that survives
 * a unit file being edited: nothing in this service prints there in the first place.
 *
 * <p>The paths driven are the ones that would print if any did — a store being opened, a request
 * answered, a peer sending something unreadable, and the whole process being closed.
 */
class DiagnosticsNeverReachTheClientTest {

  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  @TempDir Path runtimeDirectory;

  @Test
  void nothingTheServiceDoesIsWrittenToStandardOutput() throws IOException {
    Path socketPath = runtimeDirectory.resolve("authentication.sock");
    ServiceHarness.provisionOperatorIn(
        runtimeDirectory, ServiceHarness.CHEAP, OPERATOR, OPERATOR_PASSWORD);

    ByteArrayOutputStream whatWouldHaveGoneToTheClient = new ByteArrayOutputStream();
    PrintStream realStandardOutput = System.out;
    System.setOut(new PrintStream(whatWouldHaveGoneToTheClient, true, StandardCharsets.UTF_8));
    try (ServiceProcess process =
        ServiceProcess.start(
            new BoundListeningChannelSource(socketPath),
            ServiceHarness.storeFileIn(runtimeDirectory),
            ServiceHarness.CHEAP)) {

      try (ServiceClient client = ServiceClient.connect(socketPath)) {
        client.send(new AskIfBootstrapNeeded());
        client.send(new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));
        client.send(new Authenticate(OPERATOR, "wrong".toCharArray(), Role.OPERATOR));
      }

      byte[] notAMessage = "not a message".getBytes(StandardCharsets.UTF_8);
      try (TransportClient rogue = TransportClient.connect(socketPath)) {
        assertThrows(EOFException.class, () -> rogue.request(notAMessage));
      }
    } finally {
      System.setOut(realStandardOutput);
    }

    assertEquals(
        "",
        whatWouldHaveGoneToTheClient.toString(StandardCharsets.UTF_8),
        "the service wrote to standard output, which under socket activation is one unit-file"
            + " line away from being a client's connection");
  }
}
