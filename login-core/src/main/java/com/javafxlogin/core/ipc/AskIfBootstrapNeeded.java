package com.javafxlogin.core.ipc;

/**
 * Asks whether the single Administrator is still to be created, which is what tells a client
 * whether to show the first-run wizard or the login screen.
 *
 * <p>It carries nothing and is answered for anyone who can reach the socket. That is deliberate:
 * the answer is what a fresh install shows the moment it opens a window, so refusing to say it
 * would hide nothing and would leave a client with no way to choose which window to open.
 *
 * <p>Being told the wizard is needed is not being allowed to run it. The {@link Bootstrap} that
 * follows is refused unless the peer administers the machine.
 */
public record AskIfBootstrapNeeded() implements Request {

  /** Redacted like every request, though this one has nothing in it to redact. */
  @Override
  public String toString() {
    return "AskIfBootstrapNeeded[]";
  }
}
