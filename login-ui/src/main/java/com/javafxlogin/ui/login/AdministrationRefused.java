package com.javafxlogin.ui.login;

import java.util.Objects;

/**
 * The AuthenticationService refused something the administration panel asked for, and why.
 *
 * <p>One record across every administration request, because the refusals are the same refusals: a
 * Session that ended, a Session that is not an Administrator's, and a name that is not what the
 * request needed it to be. What differs between the requests is what a successful one carries, and
 * that is where they have types of their own.
 *
 * @param reason the service's own, carried through rather than interpreted here
 */
public record AdministrationRefused(AdministrationRefusedReason reason)
    implements AdministrationOutcome,
        AccountListing,
        AccountProvisioned,
        ExportOutcome,
        BackupOutcome,
        RestoreOutcome {

  public AdministrationRefused {
    Objects.requireNonNull(reason, "reason");
  }
}
