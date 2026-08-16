package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * The person holding this Session has read the notice that their password was reset, and it need not
 * be shown again.
 *
 * <p>It exists because the notice is the one thing this service says that a person has to actually
 * receive. Everything else it answers is acted on by the client immediately — a Session is granted
 * or it is not — but a sentence on a screen is delivered only when somebody has looked at it, and a
 * client that died between being told and drawing the window would otherwise have spent the only
 * copy. So the service keeps saying it, on every admission, until this arrives.
 *
 * <p>It carries the Session rather than an Account name, and that is the whole of its authorisation:
 * only the person who proved they hold the Account can say they were told about it. Asking is not
 * activity — reading a notice is not work, and acknowledging one does not restart the countdown on
 * an Operator who has walked away from the screen it was on.
 *
 * <p>Answered with {@link Ok}, including where there was nothing to acknowledge: what the caller
 * asked for is that the notice be over, and afterwards it is.
 *
 * @param token the Session of whoever was told
 */
public record AcknowledgePasswordReset(SessionToken token) implements Request {

  public AcknowledgePasswordReset {
    Objects.requireNonNull(token, "token");
  }

  /** Redacted whole: the token names a live Session. */
  @Override
  public String toString() {
    return "AcknowledgePasswordReset[redacted]";
  }
}
