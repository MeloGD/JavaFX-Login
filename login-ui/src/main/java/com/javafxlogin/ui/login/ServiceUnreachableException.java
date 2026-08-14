package com.javafxlogin.ui.login;

/**
 * The AuthenticationService could not be reached, or answered something that was not an answer.
 *
 * <p>It is not a refused login and must never be shown as one: the remedy is to get the service
 * running, not to retype a password. ADR-0002 asks for more than this eventually — "not running",
 * "incompatible version" and "socket not accessible" have different remedies and the client should
 * distinguish them — which is the startup diagnostics ticket. Until then there is one honest
 * failure here rather than three guessed ones.
 */
public class ServiceUnreachableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ServiceUnreachableException(String message) {
    super(message);
  }

  public ServiceUnreachableException(String message, Throwable cause) {
    super(message, cause);
  }
}
