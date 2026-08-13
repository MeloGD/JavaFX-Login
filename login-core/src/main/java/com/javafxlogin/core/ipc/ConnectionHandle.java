package com.javafxlogin.core.ipc;

/**
 * The connection a request arrived on, as much of it as the AuthenticationService
 * needs to see: whether it is still there, and a way to be told when it is not.
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
   * Runs {@code listener} when the connection closes, or immediately if it already has.
   *
   * <p>A listener runs exactly once. It must not block or throw: it is run on
   * whichever thread noticed the close, and a failure in one listener must not stop
   * the others from running.
   */
  void whenClosed(Runnable listener);
}
