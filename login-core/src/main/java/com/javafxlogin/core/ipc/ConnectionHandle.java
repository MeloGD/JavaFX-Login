package com.javafxlogin.core.ipc;

import java.util.Optional;

/**
 * The connection a request arrived on, as much of it as the AuthenticationService
 * needs to see: whether it is still there, a way to be told when it is not, and who
 * the operating system says is at the other end.
 *
 * <p>A Session is bound to its connection. When the client dies the kernel closes
 * the socket and the Session ends immediately — no heartbeats, and no Operator
 * locked out by a crashed client. For the service that is all a dying connection is:
 * a signal. Keeping it to this interface is what lets the service be tested with no
 * socket at all, against a handle a test closes by hand.
 */
public interface ConnectionHandle {

  /** Whether the client is still connected. */
  boolean isOpen();

  /**
   * Who the operating system says is running the process at the other end, or empty
   * where it will not say.
   *
   * <p>Empty is not "nobody" and must never be read as "anybody": a platform that
   * cannot name its peer is one where an authorisation resting on the answer has to
   * be refused. The answer is fixed when the connection is accepted and does not
   * change afterwards, so it outlives the peer.
   */
  Optional<Peer> peer();

  /**
   * Runs {@code listener} when the connection closes, or immediately if it already has.
   *
   * <p>A listener runs exactly once. It must not block or throw: it is run on
   * whichever thread noticed the close, and a failure in one listener must not stop
   * the others from running.
   */
  void whenClosed(Runnable listener);
}
