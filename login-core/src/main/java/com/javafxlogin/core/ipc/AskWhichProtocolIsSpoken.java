package com.javafxlogin.core.ipc;

/**
 * Asks the AuthenticationService which version of the message catalogue it speaks.
 *
 * <p>It is the first thing a client sends and the only thing it can send before it knows the answer,
 * which is why it carries nothing: a request with a field in it is a request a later version could
 * want to change, and {@link ProtocolVersion} explains why neither this message nor its answer may
 * ever change at all.
 *
 * <p>It does not say what this client speaks. The client compares the answer against its own number
 * and decides for itself, so a service is never asked to be tolerant of a version it has not heard
 * of — the party that has to act on the disagreement is the one that finds it.
 *
 * <p>Answered for anyone who can reach the socket, with no Session and nothing of the
 * CredentialStore behind it. A client asks it precisely because it has no Account yet.
 */
public record AskWhichProtocolIsSpoken() implements Request {

  /** Redacted like every request, though this one has nothing in it to redact. */
  @Override
  public String toString() {
    return "AskWhichProtocolIsSpoken[]";
  }
}
