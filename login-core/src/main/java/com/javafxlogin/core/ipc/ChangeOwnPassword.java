package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Changes the password of the Account whose Session this is, and rewraps its copy of the DataKey so
 * that rotating a password costs the password and not the secrets.
 *
 * <p>The Account is the Session's own and is never named by the client: a request that carried a name
 * would be a request a patched client could point at somebody else. The current password is carried
 * as well as the new one, because a live Session is not proof that the person at the keyboard is the
 * one who opened it — ASVS asks for the password again at the moment it is changed, and a Session
 * left open on an unattended machine is exactly why.
 *
 * <p>A wrong current password is refused as an authentication failure and counted like one, so that
 * this is not the one place in the system where guessing is free.
 *
 * <p>Answered with {@link Ok}, with a {@link PolicyRefused} where the new password breaks a rule,
 * with a {@link Denied} where the current one is wrong or the Account has been locked out, and with
 * {@link SessionEnded} where the Session is no longer live.
 *
 * @param token the Session asking, whose Account this changes
 * @param currentPassword what the Account authenticates with now
 * @param newPassword what it will authenticate with, and what its wrapped DataKey will be under
 */
public record ChangeOwnPassword(SessionToken token, char[] currentPassword, char[] newPassword)
    implements Request {

  public ChangeOwnPassword {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(currentPassword, "currentPassword");
    Objects.requireNonNull(newPassword, "newPassword");
  }

  /** Redacted whole: two passwords and a token. */
  @Override
  public String toString() {
    return "ChangeOwnPassword[redacted]";
  }
}
