package com.javafxlogin.core.ipc;

import java.io.IOException;

/**
 * No listening channel could be obtained, so the AuthenticationService must not start.
 *
 * <p>Failing here is the point. The alternative — quietly binding a socket of the
 * service's own when the expected one is absent — would produce a channel with
 * whatever ownership and mode {@code umask} happened to give it, which is exactly
 * what the declarative systemd socket exists to prevent.
 */
public final class ListeningChannelUnavailableException extends IOException {

  private static final long serialVersionUID = 1L;

  public ListeningChannelUnavailableException(String message) {
    super(message);
  }
}
