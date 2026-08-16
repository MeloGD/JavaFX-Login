package com.javafxlogin.core.ipc;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.session.SessionToken;
import java.util.Objects;

/**
 * Creates an Account, and deliberately carries no password.
 *
 * <p>This is ASVS 5.0 §6.4.6 in the shape of a message: an Administrator may bring an Account into
 * existence and may not choose what it authenticates with. What comes back is an {@link
 * EnrolmentIssued} holding a one-time secret to hand over, which the person who will use the Account
 * turns into a password of their own with a {@link CompleteEnrolment}.
 *
 * <p>Answered with an {@link ErrorResponse} where the Session is not an Administrator's, where the
 * name is taken, or where the Role asked for is the Administrator's — there is exactly one of those
 * and it is the one Account that is never enrolled. A name the AccountPolicy refuses comes back as a
 * {@link PolicyRefused}, assessed in the service so that a patched client cannot skip it.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param accountName the name the new Account will be known by
 * @param role the capability set it will hold
 */
public record CreateAccount(SessionToken token, String accountName, Role role) implements Request {

  public CreateAccount {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(role, "role");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "CreateAccount[redacted]";
  }
}
