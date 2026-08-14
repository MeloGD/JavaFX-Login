package com.javafxlogin.core.ipc;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The unprivileged side of the channel, speaking messages rather than bytes.
 *
 * <p>It is the whole of what a client may ask the AuthenticationService: a {@link Request} goes
 * out, a {@link Response} comes back, and the client is told the outcome and nothing else. No
 * credential file is opened here and no password is verified here — that is the point of the
 * split, and the reason this half can run as the person at the keyboard.
 *
 * <p>One connection carries one Session, so a client keeps its {@code ServiceClient} for as long
 * as the Session lasts: closing it is what ends the Session, and a client that dies has the kernel
 * do it for them.
 *
 * <p>Not thread-safe, for the same reason {@link TransportClient} is not: one request at a time.
 */
public final class ServiceClient implements Closeable {

  private final TransportClient transport;

  private ServiceClient(TransportClient transport) {
    this.transport = transport;
  }

  /**
   * Connects to the AuthenticationService listening at {@code socketPath}.
   *
   * @throws IOException if there is nothing to connect to, which on Linux means the service could
   *     not be socket-activated
   */
  public static ServiceClient connect(Path socketPath) throws IOException {
    return new ServiceClient(TransportClient.connect(socketPath));
  }

  /**
   * Sends one request and waits for the answer.
   *
   * @throws IOException if the connection failed or the service closed it instead of answering
   * @throws MalformedMessageException if the answer is not a message this build reads
   */
  public Response send(Request request) throws IOException {
    return MessageCodec.decodeResponse(transport.request(MessageCodec.encode(request)));
  }

  /** Whether the connection is still up. A Session lasts no longer than this. */
  public boolean isOpen() {
    return transport.isOpen();
  }

  /** Closes the connection, which is what ends the Session behind it. */
  @Override
  public void close() throws IOException {
    transport.close();
  }
}
