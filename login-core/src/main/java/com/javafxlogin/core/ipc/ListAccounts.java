package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Asks for every Account this deployment holds, which is what the administration panel lists.
 *
 * <p>Answered with an {@link AccountsListed}, with an {@link ErrorResponse} where the Session is not
 * an Administrator's, and with a {@link SessionEnded} where the Session is no longer live. The
 * refusal is the panel's whole authorisation: a client that drew the screen without one would be
 * drawing an empty list, because the account list lives on the far side of this request.
 *
 * <p>It is the one request that reads the CredentialStore as a whole, and it carries no name to
 * look up for that reason: an Administrator manages the deployment, and a request that answered
 * about one Account at a time would let a Session that is not theirs ask the same questions one
 * name at a time.
 *
 * @param token the Session asking, which must be an Administrator's
 */
public record ListAccounts(SessionToken token) implements Request {

  public ListAccounts {
    Objects.requireNonNull(token, "token");
  }

  /** Redacts the token, as every request carrying one does. */
  @Override
  public String toString() {
    return "ListAccounts[token=redacted]";
  }
}
