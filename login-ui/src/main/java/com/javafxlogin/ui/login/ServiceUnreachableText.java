package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.ServiceUnreachableReason;

/**
 * Names what a person is told when this application will not start because the
 * AuthenticationService could not be asked.
 *
 * <p>Exhaustive on purpose, as {@link SessionEndedText} is. The whole of issue #16 is that the three
 * reasons are told apart, so a reason added in the transport and named nowhere here would put a
 * blank on the one screen that exists to say something.
 *
 * <p>Each sentence names a remedy rather than describing a failure. "Something went wrong" helps
 * nobody: what the person needs to know is whether to start the service, install a matching version,
 * or ask to be put in a group.
 *
 * <p>Keys and not sentences, for the reason they are keys everywhere else here — nothing in this
 * package holds a word of anybody's language.
 */
final class ServiceUnreachableText {

  /** The heading of the window, and its title: this application is not going to start. */
  static final String CANNOT_START = "service.cannot-start";

  private ServiceUnreachableText() {}

  static String keyFor(ServiceUnreachableReason reason) {
    return switch (reason) {
      case NOT_RUNNING -> "service.not-running";
      case INCOMPATIBLE_VERSION -> "service.incompatible-version";
      case SOCKET_NOT_ACCESSIBLE -> "service.socket-not-accessible";
    };
  }
}
