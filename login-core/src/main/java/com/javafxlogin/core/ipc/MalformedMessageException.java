package com.javafxlogin.core.ipc;

/**
 * The payload of a whole frame is not a message this protocol can read.
 *
 * <p>The remedy is {@link MalformedFrameException}'s: the connection goes, and nothing that
 * arrived on it is acted on. It is unchecked because it is raised from inside {@link
 * RequestHandler#handle}, which promises no checked exception — the transport treats anything
 * thrown there as a connection that cannot be left half-answered, which is exactly what this is.
 *
 * <p>It is also how a version skew arrives: a client built against a later protocol sends a type
 * this build has never heard of. Refusing it is the honest answer; telling the two apart is the
 * startup diagnostics ticket's problem, not the parser's.
 */
public class MalformedMessageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MalformedMessageException(String message) {
    super(message);
  }

  public MalformedMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
