package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 2: the transport on a real {@code AF_UNIX} socket with a stub handler behind
 * it. No CredentialStore, no crypto, no AuthenticationService — a socket in a
 * temporary directory and a handler that echoes.
 *
 * <p>Everything here runs unprivileged and with no display.
 */
class TransportTest {

  private static final Duration PATIENCE = Duration.ofSeconds(5);

  @TempDir Path runtimeDirectory;

  private Path socketPath;
  private StubHandler handler;
  private TransportServer server;

  @BeforeEach
  void startServer() throws IOException {
    socketPath = runtimeDirectory.resolve("authentication.sock");
    handler = new StubHandler();
    server = TransportServer.start(new BoundListeningChannelSource(socketPath), handler);
  }

  @AfterEach
  void stopServer() throws IOException {
    if (server != null) {
      server.close();
    }
  }

  @Test
  void completesARequestResponseRoundTripOverAUnixDomainSocket() throws Exception {
    assertTrue(Files.exists(socketPath), "the listening socket should exist at its path");

    try (TransportClient client = TransportClient.connect(socketPath)) {
      byte[] response = client.request(bytes("{\"type\":\"Authenticate\"}"));

      assertEquals("echo:{\"type\":\"Authenticate\"}", text(response));
    }
  }

  @Test
  void carriesSeveralRequestsOverOneConnection() throws Exception {
    try (TransportClient client = TransportClient.connect(socketPath)) {
      assertEquals("echo:one", text(client.request(bytes("one"))));
      assertEquals("echo:two", text(client.request(bytes("two"))));
      assertEquals("echo:three", text(client.request(bytes("three"))));
    }
  }

  @Test
  void carriesAFrameAtTheCapUnchanged() throws Exception {
    byte[] request = new byte[FrameCodec.MAX_FRAME_BYTES - "echo:".length()];
    Arrays.fill(request, (byte) 'x');

    try (TransportClient client = TransportClient.connect(socketPath)) {
      byte[] response = client.request(request);

      assertEquals(FrameCodec.MAX_FRAME_BYTES, response.length);
      assertArrayEquals(
          request, Arrays.copyOfRange(response, "echo:".length(), response.length));
    }
  }

  @Test
  void reassemblesARequestSplitAcrossSeveralWrites() throws Exception {
    byte[] frame = FrameCodec.encode(bytes("split across writes"));

    try (RawClient raw = rawConnect()) {
      for (byte b : frame) {
        raw.write(new byte[] {b});
      }

      assertEquals("echo:split across writes", text(raw.nextFrame()));
    }
  }

  @Test
  void separatesSeveralRequestsArrivingInASingleWrite() throws Exception {
    byte[] first = FrameCodec.encode(bytes("first"));
    byte[] second = FrameCodec.encode(bytes("second"));
    byte[] both = new byte[first.length + second.length];
    System.arraycopy(first, 0, both, 0, first.length);
    System.arraycopy(second, 0, both, first.length, second.length);

    try (RawClient raw = rawConnect()) {
      raw.write(both);

      assertEquals("echo:first", text(raw.nextFrame()));
      assertEquals("echo:second", text(raw.nextFrame()));
    }
  }

  @Test
  void refusesAnOversizedDeclarationWithoutReadingItsBodyAndClosesTheConnection()
      throws Exception {
    try (RawClient raw = rawConnect()) {
      raw.write(lengthPrefix(FrameCodec.MAX_FRAME_BYTES + 1));

      raw.assertClosedByServer();
      assertTrue(handler.requests().isEmpty(), "an over-cap frame must never reach the handler");
    }
  }

  @Test
  void refusesAnOversizedDeclarationWithoutWaitingForTheBodyBehindIt() throws Exception {
    // The declaration promises 1 MiB and one more byte. Only a few hundred follow, and
    // the connection still goes: the refusal came from the prefix, so the body behind it
    // was never waited for and never read.
    byte[] startOfABody = new byte[512];
    Arrays.fill(startOfABody, (byte) 'x');

    try (RawClient raw = rawConnect()) {
      raw.write(lengthPrefix(FrameCodec.MAX_FRAME_BYTES + 1));
      try {
        raw.write(startOfABody);
      } catch (IOException refusalBeatUsToIt) {
        // The connection was already gone before the body reached the wire. That is
        // the property under test arriving early, not a failure.
      }

      raw.assertClosedByServer();
      assertTrue(handler.requests().isEmpty(), "an over-cap frame must never reach the handler");
    }
  }

  @Test
  void closesTheConnectionOnAMalformedFrame() throws Exception {
    try (RawClient raw = rawConnect()) {
      raw.write(lengthPrefix(0));

      raw.assertClosedByServer();
      assertTrue(handler.requests().isEmpty(), "a malformed frame must never reach the handler");
    }
  }

  @Test
  void closesTheConnectionOnATruncatedFrameRatherThanGuessingAtIt() throws Exception {
    try (RawClient raw = rawConnect()) {
      raw.write(lengthPrefix(64));
      raw.write(bytes("only the first few bytes"));
      raw.stopWriting();

      raw.assertClosedByServer();
      assertTrue(handler.requests().isEmpty(), "a truncated frame must never reach the handler");
    }
  }

  @Test
  void closesItsConnectionWhenTheServiceAnswersWithSomethingItCannotRead() throws Exception {
    Path roguePath = runtimeDirectory.resolve("rogue.sock");
    try (ServerSocketChannel rogue = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      rogue.bind(UnixDomainSocketAddress.of(roguePath));
      Thread.ofVirtual().start(() -> answerWithAnOverCapDeclaration(rogue));

      try (TransportClient client = TransportClient.connect(roguePath)) {
        assertThrows(FrameTooLargeException.class, () -> client.request(bytes("hello")));
        assertFalse(client.isOpen(), "an unreadable answer must cost the connection");
      }
    }
  }

  private static void answerWithAnOverCapDeclaration(ServerSocketChannel listening) {
    try (SocketChannel accepted = listening.accept()) {
      accepted.read(ByteBuffer.allocate(64));
      ByteBuffer answer = ByteBuffer.wrap(lengthPrefix(FrameCodec.MAX_FRAME_BYTES + 1));
      while (answer.hasRemaining()) {
        accepted.write(answer);
      }
      Thread.sleep(PATIENCE.toMillis());
    } catch (IOException | InterruptedException expected) {
      // The client closing on us is the point of the test.
    }
  }

  @Test
  void servesConcurrentConnectionsWithoutEitherSeeingTheOthersTraffic() throws Exception {
    try (TransportClient alice = TransportClient.connect(socketPath);
        TransportClient bob = TransportClient.connect(socketPath)) {

      assertEquals("echo:alice-1", text(alice.request(bytes("alice-1"))));
      assertEquals("echo:bob-1", text(bob.request(bytes("bob-1"))));
      assertEquals("echo:alice-2", text(alice.request(bytes("alice-2"))));
      assertEquals("echo:bob-2", text(bob.request(bytes("bob-2"))));

      List<ConnectionHandle> connections = handler.connections();
      assertEquals(2, connections.size(), "each client should have its own connection");
      assertNotSame(connections.get(0), connections.get(1));
    }
  }

  @Test
  void keepsServingOtherConnectionsWhenOneSendsSomethingItCannotRead() throws Exception {
    try (TransportClient survivor = TransportClient.connect(socketPath)) {
      assertEquals("echo:before", text(survivor.request(bytes("before"))));

      try (RawClient offender = rawConnect()) {
        offender.write(lengthPrefix(FrameCodec.MAX_FRAME_BYTES + 1));
        offender.assertClosedByServer();
      }

      assertEquals("echo:after", text(survivor.request(bytes("after"))));
    }
  }

  @Test
  void servesConcurrentConnectionsUnderLoadWithoutCrossingTheirTraffic() throws Exception {
    int clients = 8;
    int roundTrips = 20;
    CountDownLatch finished = new CountDownLatch(clients);
    List<String> mismatches = new CopyOnWriteArrayList<>();

    for (int i = 0; i < clients; i++) {
      String name = "client-" + i;
      Thread.ofVirtual().start(() -> {
        try (TransportClient client = TransportClient.connect(socketPath)) {
          for (int call = 0; call < roundTrips; call++) {
            String request = name + "-" + call;
            String response = text(client.request(bytes(request)));
            if (!response.equals("echo:" + request)) {
              mismatches.add(request + " got " + response);
            }
          }
        } catch (Exception e) {
          mismatches.add(name + " failed: " + e);
        } finally {
          finished.countDown();
        }
      });
    }

    assertTrue(finished.await(PATIENCE.toSeconds(), TimeUnit.SECONDS), "clients did not finish");
    assertEquals(List.of(), mismatches);
  }

  @Test
  void reportsTheConnectionOpenWhileTheClientIsThere() throws Exception {
    try (TransportClient client = TransportClient.connect(socketPath)) {
      client.request(bytes("hello"));

      assertTrue(handler.connections().get(0).isOpen());
    }
  }

  @Test
  void signalsTheHandleWhenTheClientDisappearsSoASessionCanBeEnded() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);

    TransportClient client = TransportClient.connect(socketPath);
    client.request(bytes("hello"));
    ConnectionHandle connection = handler.connections().get(0);
    connection.whenClosed(closed::countDown);

    client.close();

    assertTrue(closed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
        "closing the connection should signal the handle");
    assertFalse(connection.isOpen());
  }

  @Test
  void signalsAHandleRegisteredAfterTheConnectionAlreadyClosed() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);

    TransportClient client = TransportClient.connect(socketPath);
    client.request(bytes("hello"));
    ConnectionHandle connection = handler.connections().get(0);
    client.close();
    await(() -> !connection.isOpen());

    connection.whenClosed(closed::countDown);

    assertTrue(closed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
        "a late listener should still learn the connection is gone");
  }

  @Test
  void signalsTheHandleWhenTheServerItselfStops() throws Exception {
    CountDownLatch closed = new CountDownLatch(1);

    try (TransportClient client = TransportClient.connect(socketPath)) {
      client.request(bytes("hello"));
      handler.connections().get(0).whenClosed(closed::countDown);

      server.close();

      assertTrue(closed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS),
          "stopping the server should signal every live connection");
    }
  }

  @Test
  void stopsCleanlyWhileClientsAreStillArriving() throws Exception {
    CountDownLatch connecting = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(4);

    for (int i = 0; i < 4; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          connecting.await();
          for (int attempt = 0; attempt < 50; attempt++) {
            try (TransportClient client = TransportClient.connect(socketPath)) {
              client.request(bytes("hello"));
            } catch (IOException expectedOnceStopped) {
              return;
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          finished.countDown();
        }
      });
    }

    connecting.countDown();
    server.close();

    assertTrue(finished.await(PATIENCE.toSeconds(), TimeUnit.SECONDS), "clients did not finish");
    assertFalse(Files.exists(socketPath));
    for (ConnectionHandle connection : handler.connections()) {
      assertFalse(connection.isOpen(), "every connection should have been closed and signalled");
    }
  }

  @Test
  void removesItsSocketFileWhenItBoundOneItself() throws Exception {
    server.close();

    assertFalse(Files.exists(socketPath), "a socket this process bound should not be left behind");
  }

  // --- stub handler and raw-socket helpers -------------------------------------------------

  /** Echoes, and records what it was handed so the tests can assert on it. */
  private static final class StubHandler implements RequestHandler {

    private final List<byte[]> requests = new CopyOnWriteArrayList<>();
    private final List<ConnectionHandle> connections = new CopyOnWriteArrayList<>();

    @Override
    public byte[] handle(byte[] request, ConnectionHandle connection) {
      requests.add(request);
      if (!connections.contains(connection)) {
        connections.add(connection);
      }
      return bytes("echo:" + text(request));
    }

    List<byte[]> requests() {
      return requests;
    }

    List<ConnectionHandle> connections() {
      return connections;
    }
  }

  private RawClient rawConnect() throws IOException {
    return new RawClient(SocketChannel.open(UnixDomainSocketAddress.of(socketPath)));
  }

  /**
   * A client that writes exactly the bytes it is given, so the tests can send things
   * {@link TransportClient} would never produce.
   */
  private static final class RawClient implements AutoCloseable {

    private final SocketChannel channel;
    private final FrameDecoder decoder = new FrameDecoder();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(4096);

    private RawClient(SocketChannel channel) {
      this.channel = channel;
    }

    void write(byte[] bytes) throws IOException {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    }

    void stopWriting() throws IOException {
      channel.shutdownOutput();
    }

    /** Reads until one whole frame is available, failing if the peer goes away first. */
    byte[] nextFrame() {
      return assertTimeoutPreemptively(PATIENCE, () -> {
        while (true) {
          Optional<byte[]> frame = decoder.next();
          if (frame.isPresent()) {
            return frame.get();
          }
          readBuffer.clear();
          if (channel.read(readBuffer) == -1) {
            throw new AssertionError("connection closed before a whole frame arrived");
          }
          readBuffer.flip();
          decoder.append(readBuffer);
        }
      });
    }

    void assertClosedByServer() {
      assertTimeoutPreemptively(PATIENCE, () -> {
        readBuffer.clear();
        assertEquals(-1, channel.read(readBuffer), "the server should have closed the connection");
      });
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) {
    assertTimeoutPreemptively(PATIENCE, () -> {
      while (!condition.getAsBoolean()) {
        Thread.sleep(5);
      }
    });
  }

  private static byte[] lengthPrefix(int declaredLength) {
    return new byte[] {
      (byte) (declaredLength >>> 24),
      (byte) (declaredLength >>> 16),
      (byte) (declaredLength >>> 8),
      (byte) declaredLength
    };
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
