package com.javafxlogin.core.ipc;

import java.io.EOFException;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The unprivileged side of the channel: connects to the socket and exchanges framed
 * requests for framed responses.
 *
 * <p>On Linux the socket is always present, because systemd created it — connecting
 * is what starts the AuthenticationService, and the connection waits in the backlog
 * while the service boots. There is nothing to poll and no cold-start race.
 *
 * <p>The cap applies in this direction too: a response declaring more than
 * {@link FrameCodec#MAX_FRAME_BYTES} is refused without its body being read.
 *
 * <p>Not thread-safe. A connection carries one Session and one request at a time.
 */
public final class TransportClient implements AutoCloseable {

  private static final int READ_BUFFER_BYTES = 16 * 1024;

  private final SocketChannel channel;
  private final FrameDecoder decoder = new FrameDecoder();
  private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);

  private TransportClient(SocketChannel channel) {
    this.channel = channel;
  }

  /** Connects to the socket at {@code socketPath}. */
  public static TransportClient connect(Path socketPath) throws IOException {
    return new TransportClient(SocketChannel.open(UnixDomainSocketAddress.of(socketPath)));
  }

  /**
   * Sends one request and waits for its response.
   *
   * @throws EOFException if the service closed the connection instead of answering
   * @throws MalformedFrameException if the answer is not a frame this protocol can read
   */
  public byte[] request(byte[] payload) throws IOException {
    try {
      return exchange(payload);
    } catch (MalformedFrameException e) {
      // The same rule applies in this direction: an answer that cannot be read is
      // never guessed at, and the connection it arrived on is no longer trustworthy.
      closeQuietly();
      throw e;
    }
  }

  private byte[] exchange(byte[] payload) throws IOException {
    ByteBuffer frame = ByteBuffer.wrap(FrameCodec.encode(payload));
    while (frame.hasRemaining()) {
      channel.write(frame);
    }

    while (true) {
      Optional<byte[]> response = decoder.next();
      if (response.isPresent()) {
        return response.get();
      }
      readBuffer.clear();
      if (channel.read(readBuffer) == -1) {
        throw new EOFException("The AuthenticationService closed the connection");
      }
      readBuffer.flip();
      decoder.append(readBuffer);
    }
  }

  /** Whether the connection is still up. A Session lasts no longer than this. */
  public boolean isOpen() {
    return channel.isOpen();
  }

  /** Closes the connection, which is what ends the Session behind it. */
  @Override
  public void close() throws IOException {
    channel.close();
  }

  private void closeQuietly() {
    try {
      channel.close();
    } catch (IOException ignored) {
      // Already reporting a worse failure to the caller.
    }
  }
}
