package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.harness.TickingClock;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.ServiceClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole of what "in use" means to the privileged process, over a real socket.
 *
 * <p>{@link IdleShutdown} decides when a service that is not in use stops; this is where what it
 * asks about is joined to the two things that can be going on — a client still connected, and a
 * Session still live. Both are checked across the wire rather than against a stub, because a
 * connection nobody noticed closing is exactly the defect that would leave a privileged JVM up for
 * good.
 *
 * <p>What systemd does with the process afterwards is in
 * {@code docs/manual-checks/linux-service-activation.md}.
 */
class ServiceStopsWhenNobodyIsUsingItTest {

  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final Duration UNTIL_THE_KERNEL_NOTICES = Duration.ofSeconds(5);

  @TempDir Path runtimeDirectory;

  private final TickingClock clock = TickingClock.startingAt(Instant.parse("2026-08-21T12:00:00Z"));

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
  void isNotInUseBeforeAnybodyHasConnected() {
    assertFalse(process.inUse());
  }

  @Test
  void isInUseWhileAClientIsConnected() throws IOException {
    // A connection with no Session behind it is a person at the login window who has not typed a
    // password yet. Exiting under them would drop the connection their next attempt goes over.
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new AskIfBootstrapNeeded());

      assertTrue(process.inUse());
    }
  }

  @Test
  void isInUseWhileASessionIsLive() throws IOException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      assertInstanceOf(
          Granted.class,
          client.send(
              new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR)));

      assertTrue(process.inUse());
    }
  }

  @Test
  void isNotInUseOnceTheClientHasGone() throws IOException, InterruptedException {
    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new Authenticate(OPERATOR, OPERATOR_PASSWORD.toCharArray(), Role.OPERATOR));
    }

    assertTrue(waitUntilNobodyIsUsingIt(), "the closed connection was never noticed");
  }

  /**
   * The join itself: the countdown runs against a real process, and running out closes it. Five
   * minutes are moved rather than waited out, as they are in {@link IdleShutdownTest}.
   */
  @Test
  void stopsTheProcessOnceTheIdlePeriodHasPassedWithNobodyUsingIt() throws IOException {
    IdleShutdown shutdown = theCountdownAgainstThisProcess();

    clock.passes(IdleShutdown.IDLE_PERIOD);
    shutdown.reconsider();

    assertTrue(shutdown.hasStopped());
    assertThrows(IOException.class, () -> ServiceClient.connect(socketPath));
  }

  @Test
  void keepsServingWhileAClientIsStillThere() throws IOException {
    IdleShutdown shutdown = theCountdownAgainstThisProcess();

    try (ServiceClient client = ServiceClient.connect(socketPath)) {
      client.send(new AskIfBootstrapNeeded());

      clock.passes(IdleShutdown.IDLE_PERIOD.multipliedBy(3));
      shutdown.reconsider();

      assertFalse(shutdown.hasStopped());
      assertInstanceOf(BootstrapNeeded.class, client.send(new AskIfBootstrapNeeded()));
    }
  }

  /** The countdown this process's own life runs against, with the five minutes moved by hand. */
  private IdleShutdown theCountdownAgainstThisProcess() {
    return new IdleShutdown(clock, process::inUse, process::close);
  }

  /**
   * The service notices a connection go when it reads the end of it, which happens on the
   * connection's own thread rather than on this one — so this waits for it rather than assuming
   * it has already happened.
   */
  private boolean waitUntilNobodyIsUsingIt() throws InterruptedException {
    Instant giveUpAt = Instant.now().plus(UNTIL_THE_KERNEL_NOTICES);
    while (Instant.now().isBefore(giveUpAt)) {
      if (!process.inUse()) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }
}
