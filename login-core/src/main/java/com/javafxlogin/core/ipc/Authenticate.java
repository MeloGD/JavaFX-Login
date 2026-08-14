package com.javafxlogin.core.ipc;

import com.javafxlogin.core.account.Role;
import java.util.Objects;

/**
 * Offers a name and a password, and says which Role the client is asking to act in. Answered with
 * {@link Granted} or {@link Denied}, and with nothing that distinguishes why a denial happened.
 *
 * <p>The Role is part of the question rather than of the answer, so that refusing an Administrator
 * the ProtectedFeature is a decision the service makes. A client that answered it for itself —
 * authenticating, reading the Role back and choosing which window to open — would be a client a
 * patch could talk out of the refusal.
 *
 * @param accountName matched exactly against a stored Account
 * @param password not retained by the service beyond verifying it
 * @param requestedRole the Role the client asks to act in; an Account holding a different one is
 *     refused exactly as a wrong password is
 */
public record Authenticate(String accountName, char[] password, Role requestedRole)
    implements Request {

  public Authenticate {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(requestedRole, "requestedRole");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "Authenticate[redacted]";
  }
}
