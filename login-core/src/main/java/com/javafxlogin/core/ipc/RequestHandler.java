package com.javafxlogin.core.ipc;

/**
 * What sits behind the transport: one request in, one response out.
 *
 * <p>The payloads are opaque here. The transport's job ends at delivering whole,
 * capped frames in order; what those bytes mean is the AuthenticationService's
 * business, and the message types are decoded above this interface.
 *
 * <p>The connection arrives alongside the request rather than being looked up,
 * because a Session is bound to it — the handler registers its interest in the
 * connection going away and ends the Session when it does.
 *
 * <p>Implementations are called from several connection threads at once and must be
 * thread-safe. Requests on any one connection are delivered in order, one at a time.
 */
@FunctionalInterface
public interface RequestHandler {

  /**
   * Handles one request and returns the response payload.
   *
   * <p>The response must be a non-empty payload no larger than
   * {@link FrameCodec#MAX_FRAME_BYTES}. Returning anything else, or throwing, is a
   * defect in the service rather than a wire event, and costs the connection.
   */
  byte[] handle(byte[] request, ConnectionHandle connection);
}
