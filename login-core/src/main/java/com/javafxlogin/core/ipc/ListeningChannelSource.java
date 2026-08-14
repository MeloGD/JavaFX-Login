package com.javafxlogin.core.ipc;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;

/**
 * Obtains the listening {@code AF_UNIX} channel the AuthenticationService serves on.
 *
 * <p>This is the seam ADR-0003 names: the single place where Linux and Windows
 * diverge. Linux adopts a channel systemd created and handed over; Windows binds
 * one itself. Everything above this interface — the framing, the request handling,
 * the Session model — is shared by both, which is what lets the Windows half be
 * added later without touching any of it.
 */
public interface ListeningChannelSource {

  /**
   * The bound, listening channel to accept connections on.
   *
   * @throws ListeningChannelUnavailableException if no channel can be served on, which
   *     is fatal at startup: the service must refuse to run rather than serve on a
   *     channel it was not promised
   * @throws IOException if the channel cannot be obtained
   */
  ServerSocketChannel acquire() throws IOException;

  /**
   * Cleans up whatever this source created, after the server has closed the channel.
   *
   * <p>Only a source that created something has anything to undo. A source that
   * adopted an inherited channel must leave the socket file alone — it belongs to
   * whoever created it, and removing it would break the next activation.
   */
  default void release() throws IOException {}
}
