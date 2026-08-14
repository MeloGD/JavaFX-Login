package com.javafxlogin.core.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Accepts connections on the listening channel and serves framed requests over them.
 *
 * <p>One connection is served by one thread, in order, which is what keeps a Session
 * and its connection in step and keeps two clients' traffic apart. The threads are
 * virtual, so a handler blocking on the CredentialStore costs a stack rather than a
 * platform thread.
 *
 * <p>Anything the peer sends that is not a frame this protocol can read costs the
 * connection and nothing else: no resynchronisation, no partial handling. The peer of
 * a privileged process does not get the benefit of the doubt.
 */
public final class TransportServer implements AutoCloseable {

  private static final int READ_BUFFER_BYTES = 16 * 1024;
  private static final Duration ACCEPT_RETRY_PAUSE = Duration.ofMillis(50);

  private final ListeningChannelSource source;
  private final RequestHandler handler;
  private final ServerSocketChannel listening;
  private final ExecutorService connectionThreads =
      Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Connection> liveConnections = ConcurrentHashMap.newKeySet();

  private volatile boolean running = true;

  private TransportServer(
      ListeningChannelSource source, RequestHandler handler, ServerSocketChannel listening) {
    this.source = source;
    this.handler = handler;
    this.listening = listening;
  }

  /**
   * Acquires the listening channel and starts accepting connections.
   *
   * @throws IOException if no channel can be served on, which is fatal at startup
   */
  public static TransportServer start(ListeningChannelSource source, RequestHandler handler)
      throws IOException {
    ServerSocketChannel listening = source.acquire();
    TransportServer server = new TransportServer(source, handler, listening);
    Thread.ofVirtual().name("ipc-accept").start(server::acceptConnections);
    return server;
  }

  private void acceptConnections() {
    while (running) {
      SocketChannel channel;
      try {
        channel = listening.accept();
      } catch (IOException e) {
        // The listening channel closed, or a connection died between the kernel
        // queueing it and this accept. Neither is worth ending the service over.
        if (!running || !listening.isOpen()) {
          return;
        }
        // A condition that persists — running out of descriptors, say — would
        // otherwise spin this loop at full speed on a machine already in trouble.
        if (!pauseBeforeRetrying()) {
          return;
        }
        continue;
      }
      Connection connection = new Connection(channel);
      liveConnections.add(connection);
      try {
        connectionThreads.execute(connection::serve);
      } catch (RejectedExecutionException e) {
        // The service shut down between accepting this connection and serving it.
        // Nobody is waiting on an answer yet, but the connection is ours to close.
        connection.closeNow();
        return;
      }
    }
  }

  /** Returns false if the wait was interrupted, which means the service is going down. */
  private static boolean pauseBeforeRetrying() {
    try {
      Thread.sleep(ACCEPT_RETRY_PAUSE);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Stops listening and drops every live connection, signalling each handle in turn. */
  @Override
  public void close() throws IOException {
    running = false;
    closeQuietly(listening);
    for (Connection connection : List.copyOf(liveConnections)) {
      connection.closeNow();
    }
    connectionThreads.shutdownNow();
    source.release();
  }

  private static void closeQuietly(Channel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      // Closing is the last thing done with a channel; a failure here changes nothing.
    }
  }

  /** One client's connection, and the handle the service sees it through. */
  private final class Connection implements ConnectionHandle {

    private final SocketChannel channel;
    private final FrameDecoder decoder = new FrameDecoder();
    private final List<Runnable> closeListeners = new ArrayList<>();

    private volatile boolean open = true;

    private Connection(SocketChannel channel) {
      this.channel = channel;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void whenClosed(Runnable listener) {
      synchronized (closeListeners) {
        if (open) {
          closeListeners.add(listener);
          return;
        }
      }
      run(listener);
    }

    private void serve() {
      try {
        readRequests();
      } catch (MalformedFrameException e) {
        // ADR-0003: a frame that cannot be read is never guessed at. Dropping the
        // connection is the whole response.
      } catch (IOException e) {
        // The peer went away, or the response could not be written. Same remedy.
      } catch (RuntimeException e) {
        // Either a defect in the service, or the layer above refusing a payload that
        // is not a message it reads. The remedy is the same one a malformed frame
        // gets: the connection cannot be left half-answered, so it goes.
      } finally {
        closeNow();
      }
    }

    private void readRequests() throws IOException {
      ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
      while (running) {
        buffer.clear();
        int read = channel.read(buffer);
        if (read == -1) {
          // A peer that stopped mid-frame sent a truncated frame; either way the
          // connection is over and nothing half-arrived is acted on.
          return;
        }
        buffer.flip();
        decoder.append(buffer);

        Optional<byte[]> request;
        while ((request = decoder.next()).isPresent()) {
          writeFrame(handler.handle(request.get(), this));
        }
      }
    }

    private void writeFrame(byte[] response) throws IOException {
      ByteBuffer frame = ByteBuffer.wrap(FrameCodec.encode(response));
      while (frame.hasRemaining()) {
        channel.write(frame);
      }
    }

    private void closeNow() {
      List<Runnable> listeners;
      synchronized (closeListeners) {
        if (!open) {
          return;
        }
        open = false;
        listeners = List.copyOf(closeListeners);
        closeListeners.clear();
      }
      closeQuietly(channel);
      liveConnections.remove(this);
      listeners.forEach(Connection::run);
    }

    private static void run(Runnable listener) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        // One listener failing must not stop the Session ending for the others.
      }
    }
  }
}
