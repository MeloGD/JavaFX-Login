package com.javafxlogin.core.ipc;

import java.io.IOException;

/**
 * The bytes on the wire are not a frame this protocol can read.
 *
 * <p>There is exactly one response to this: close the connection. A frame is never
 * guessed at, resynchronised or partially honoured, because the peer of the
 * privileged {@code AuthenticationService} is not trusted to have meant well.
 */
public class MalformedFrameException extends IOException {

  private static final long serialVersionUID = 1L;

  public MalformedFrameException(String message) {
    super(message);
  }
}
