package com.javafxlogin.core.session;

/**
 * Why the AuthenticationService will not go on with the Session a client named.
 *
 * <p>Being specific is safe here in a way it is not for a failed authentication: whoever is asking
 * held a SessionToken the service itself issued, so nothing in this set tells them anything they
 * were not already entitled to know. What it buys is a person being told why the window in front of
 * them closed, rather than being returned to a login screen for no stated reason.
 *
 * <p>Two ways a Session ends are deliberately absent. A Session ended by its connection closing
 * cannot be reported, because the connection an answer would travel on is the one that closed. A
 * Session ended by a logout is not reported either: the client that asked for it does not need to
 * be told twice.
 */
public enum SessionEndedReason {

  /**
   * The Session went the configured period without the Operator doing anything. Measured against
   * both clocks, so a machine that was suspended for longer than the period comes back to this.
   */
  INACTIVITY,

  /**
   * The machine's clock stopped agreeing with the clock that cannot be moved, by more than the
   * service tolerates. What produced the disagreement is not knowable from inside a JVM — a clock
   * someone set, or a machine resumed from suspend, look alike — and both mean the service can no
   * longer account for the time this Session spent idle.
   */
  CLOCK_JUMPED,

  /**
   * The token names no live Session: it was never issued, it belongs to a Session that has already
   * ended, or it arrived on a connection other than the one it was granted on. The three are one
   * answer on purpose — a token is not a bearer credential to be replayed from somewhere else, and
   * distinguishing them would say which of those a caller had got wrong.
   */
  NO_SUCH_SESSION
}
