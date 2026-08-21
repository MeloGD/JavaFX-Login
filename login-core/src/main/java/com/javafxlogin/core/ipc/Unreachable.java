package com.javafxlogin.core.ipc;

/**
 * The AuthenticationService could not be asked, and which of three ways it could not.
 *
 * @param reason the one thing the person at the keyboard needs told, because it is the one thing
 *     that decides what they can do about it
 */
public record Unreachable(ServiceUnreachableReason reason) implements ServiceReachability {

  public Unreachable {
    if (reason == null) {
      throw new IllegalArgumentException("An unreachable service is unreachable for a reason");
    }
  }
}
