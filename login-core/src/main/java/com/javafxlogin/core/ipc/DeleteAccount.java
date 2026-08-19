package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Deletes an Operator, and with them their wrapped copy of the DataKey — which is what makes
 * revocation real rather than a row disappearing from a list.
 *
 * <p>The wrap is destroyed before the Account is, and the order is the whole of it: a wrap left
 * behind by a delete that half-worked would be Vault access reachable again by creating an Account
 * with the same name.
 *
 * <p>The single Administrator cannot be deleted. There is exactly one, and an Administrator who could
 * be deleted from a Session would be a deployment nobody can administer afterwards.
 *
 * <p>Answered with {@link Ok}, with an {@link ErrorResponse} where the Session is not an
 * Administrator's, where no Account holds that name, or where the name is the Administrator's own,
 * and with {@link SessionEnded} where the Session is no longer live.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param accountName whose Account and Vault access end here
 */
public record DeleteAccount(SessionToken token, String accountName) implements Request {

  public DeleteAccount {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "DeleteAccount[redacted]";
  }
}
