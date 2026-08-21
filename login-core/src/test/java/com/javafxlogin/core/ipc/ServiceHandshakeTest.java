package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 2: what a client learns about the AuthenticationService before it draws anything, on a real
 * {@code AF_UNIX} socket with a stub behind it.
 *
 * <p>Issue #16's last criterion is that these diagnostics are exercised without a real installed
 * service, and this is where that is kept true. Nothing here installs a systemd unit, runs as root
 * or touches a CredentialStore: each case is a socket in a temporary directory with something —
 * or deliberately nothing — answering on it.
 *
 * <p>The three reasons are pinned one by one, because the whole point of the ticket is that they are
 * told apart. A test that only asserted "unreachable" would pass on a build that had collapsed them
 * back into one.
 */
class ServiceHandshakeTest {

  /**
   * Short on purpose. Every case below either answers at once or never answers at all, so the only
   * thing a long patience would buy this suite is time spent proving that waiting works.
   */
  private static final Duration BRIEFLY = Duration.ofMillis(300);

  @TempDir Path runtimeDirectory;

  private final List<AutoCloseable> stubs = new ArrayList<>();

  @AfterEach
  void stopTheStubs() throws Exception {
    for (AutoCloseable stub : stubs) {
      stub.close();
    }
  }

  /** A service of this build's own version, which is the only case that admits anybody. */
  @Test
  void aServiceSpeakingThisProtocolIsReachable() throws IOException {
    Path socketPath = answering(MessageCodec.encode(new ProtocolSpoken(ProtocolVersion.CURRENT)));

    assertInstanceOf(Reachable.class, ServiceHandshake.attemptedAt(socketPath, BRIEFLY));
  }

  /**
   * The socket unit was never installed, so there is nothing on the machine to connect to and
   * nothing that connecting could start.
   */
  @Test
  void aSocketThatIsNotThereIsAServiceThatIsNotRunning() {
    assertUnreachableFor(
        ServiceUnreachableReason.NOT_RUNNING, runtimeDirectory.resolve("never-installed.sock"));
  }

  /** A socket file left behind by something that has since gone: connecting is refused outright. */
  @Test
  void aSocketWithNothingListeningIsAServiceThatIsNotRunning() throws IOException {
    Path socketPath = runtimeDirectory.resolve("stale.sock");
    ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    listener.bind(UnixDomainSocketAddress.of(socketPath));
    listener.close();

    assertUnreachableFor(ServiceUnreachableReason.NOT_RUNNING, socketPath);
  }

  /**
   * The Linux case the ticket calls out: under socket activation the socket is always present, so a
   * service that failed to come up is not a refused connection but an accepted one that goes on
   * saying nothing. The deadline is what turns that silence into an answer.
   */
  @Test
  void aServiceThatNeverAnswersIsAServiceThatIsNotRunning() throws IOException {
    Path socketPath = listeningAndSaying(Silence.INSTANCE);

    assertTimeoutPreemptively(
        BRIEFLY.multipliedBy(10),
        () -> assertUnreachableFor(ServiceUnreachableReason.NOT_RUNNING, socketPath));
  }

  /** A service that came up far enough to accept a connection and then died on it. */
  @Test
  void aServiceThatHangsUpWithoutAnsweringIsAServiceThatIsNotRunning() throws IOException {
    Path socketPath = listeningAndSaying(Answer.byHangingUp());

    assertUnreachableFor(ServiceUnreachableReason.NOT_RUNNING, socketPath);
  }

  /** The disagreement said as a disagreement: a number, read cleanly, that is not this build's. */
  @Test
  void aServiceSpeakingAnotherVersionIsIncompatibleRatherThanUnparseable() throws IOException {
    Path socketPath =
        answering(MessageCodec.encode(new ProtocolSpoken(ProtocolVersion.CURRENT + 1)));

    assertUnreachableFor(ServiceUnreachableReason.INCOMPATIBLE_VERSION, socketPath);
  }

  /**
   * A build so far ahead that even its answer to the frozen question is not one this catalogue
   * reads. The client cannot name the version, and still must not report a parse failure: what a
   * person needs told is that the two halves are from different releases.
   */
  @Test
  void anAnswerThisBuildCannotReadAtAllIsIncompatibleRatherThanUnparseable() throws IOException {
    Path socketPath =
        answering("{\"type\":\"SpokeSomethingElse\"}".getBytes(StandardCharsets.UTF_8));

    assertUnreachableFor(ServiceUnreachableReason.INCOMPATIBLE_VERSION, socketPath);
  }

  /** Not even JSON: something is on that socket, and it is not this protocol. */
  @Test
  void anAnswerThatIsNotAMessageAtAllIsIncompatible() throws IOException {
    Path socketPath = answering("not a message".getBytes(StandardCharsets.UTF_8));

    assertUnreachableFor(ServiceUnreachableReason.INCOMPATIBLE_VERSION, socketPath);
  }

  /** A peer that does not frame the way ADR-0003 frames is the same disagreement one layer down. */
  @Test
  void anAnswerThatIsNotEvenAFrameIsIncompatible() throws IOException {
    Path socketPath = listeningAndSaying(Answer.of(new byte[] {(byte) 0xFF, 0, 0, 1, 'x'}));

    assertUnreachableFor(ServiceUnreachableReason.INCOMPATIBLE_VERSION, socketPath);
  }

  /**
   * The group-membership case. The socket's mode is what ADR-0003 has systemd set declaratively, so
   * this stands in for a person who is not in the group the installer created.
   *
   * <p>Skipped where the account running the suite is not subject to that mode in the first place:
   * root reaches every socket on the machine, so on root this would assert that a permission which
   * does not apply was applied.
   */
  @Test
  void aSocketThisAccountMayNotReachIsToldApartFromOneThatIsNotThere() throws IOException {
    assumeFalse("root".equals(System.getProperty("user.name")), "root reaches every socket");
    Path socketPath = listeningAndSaying(Silence.INSTANCE);
    Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("---------"));

    assertUnreachableFor(ServiceUnreachableReason.SOCKET_NOT_ACCESSIBLE, socketPath);
  }

  /** The budget is the whole exchange's, and it is kept even when nothing on the far side moves. */
  @Test
  void aHandshakeThatIsGoingNowhereIsGivenUpOnWithinItsPatience() throws IOException {
    Path socketPath = listeningAndSaying(Silence.INSTANCE);

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> ServiceHandshake.attemptedAt(socketPath, Duration.ofMillis(500)));
  }

  private void assertUnreachableFor(ServiceUnreachableReason expected, Path socketPath) {
    ServiceReachability found = ServiceHandshake.attemptedAt(socketPath, BRIEFLY);

    assertEquals(new Unreachable(expected), assertInstanceOf(Unreachable.class, found));
  }

  /** A socket that hands back {@code payload} in one frame and closes. */
  private Path answering(byte[] payload) throws IOException {
    return listeningAndSaying(Answer.of(FrameCodec.encode(payload)));
  }

  private Path listeningAndSaying(Stub stub) throws IOException {
    Path socketPath = runtimeDirectory.resolve("authentication.sock");
    ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    listener.bind(UnixDomainSocketAddress.of(socketPath));
    Thread accepting =
        Thread.ofVirtual()
            .name("stub-service")
            .start(
                () -> {
                  while (listener.isOpen()) {
                    try (SocketChannel connection = listener.accept()) {
                      stub.serve(connection);
                    } catch (IOException e) {
                      return;
                    }
                  }
                });
    stubs.add(
        () -> {
          listener.close();
          accepting.join();
          Files.deleteIfExists(socketPath);
        });
    return socketPath;
  }

  /** What a stub does with a connection once it has accepted one. */
  private interface Stub {
    void serve(SocketChannel connection) throws IOException;
  }

  /**
   * A service that accepted the connection and never says anything, which is what one that failed
   * to start looks like through a socket systemd is holding open on its behalf.
   */
  private enum Silence implements Stub {
    INSTANCE;

    @Override
    public void serve(SocketChannel connection) throws IOException {
      // Read until the client gives up and goes, so that the connection stays open while it waits.
      ByteBuffer ignored = ByteBuffer.allocate(1024);
      while (connection.read(ignored) >= 0) {
        ignored.clear();
      }
    }
  }

  /** A service that sends back exactly these bytes, or none at all, and hangs up. */
  private record Answer(byte[] bytes) implements Stub {

    static Answer of(byte[] bytes) {
      return new Answer(bytes);
    }

    static Answer byHangingUp() {
      return new Answer(new byte[0]);
    }

    @Override
    public void serve(SocketChannel connection) throws IOException {
      ByteBuffer question = ByteBuffer.allocate(1024);
      connection.read(question);
      ByteBuffer answer = ByteBuffer.wrap(bytes);
      while (answer.hasRemaining()) {
        connection.write(answer);
      }
    }
  }
}
