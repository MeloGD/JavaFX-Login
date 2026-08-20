package com.javafxlogin.core.account;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One Account as the administration panel lists it: everything an Administrator needs to decide
 * what to do about it, and nothing a password could be recovered from.
 *
 * <p>It is a separate type from {@link Account} on purpose. An Account carries the password hash,
 * which is the one thing ADR-0002 keeps inside the privileged process; this crosses the socket to a
 * client that runs as whoever is at the keyboard, so it is written as its own record with no field
 * a hash could travel in. A build that listed Accounts would have to remember not to send that
 * field, and remembering is what this replaces.
 *
 * @param name the name the Account is known by, matched exactly at the login prompt
 * @param role the single capability set attached to it
 * @param passwordStrength the coarse band estimated when the password was chosen, which is what an
 *     Administrator reads to find the Accounts worth nudging — or empty where the Account is
 *     awaiting enrolment and nobody has chosen a password for there to be a band of. The store
 *     keeps the weakest band on a row with no password, so that an unknown password can never read
 *     as a strong one; reporting that here as if it were a measurement would have an Administrator
 *     nudging somebody who has no password at all
 * @param languagePreference what the person using this Account reads the interface in, or empty
 *     where they have said nothing and the machine's own locale answers for them. Issue #13 is what
 *     lets it be chosen; this build lists what the CredentialStore holds
 * @param lockedFor how long this Account is refused for, or empty where it is not refused at all.
 *     It is a remaining time rather than a moment because that is what a person reading the panel
 *     is asking — and because only the AuthenticationService holds the clock the answer is against
 */
public record AccountSummary(
    String name,
    Role role,
    Optional<PasswordStrength> passwordStrength,
    Optional<Locale> languagePreference,
    Optional<Duration> lockedFor) {

  public AccountSummary {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(passwordStrength, "passwordStrength");
    Objects.requireNonNull(languagePreference, "languagePreference");
    Objects.requireNonNull(lockedFor, "lockedFor");
  }

  /**
   * The same Account as the CredentialStore holds it, before anything has been decided about its
   * Lockout.
   *
   * <p>The store has no clock and no LockoutPolicy: what it keeps is the moment a refusal runs out,
   * and turning that into "locked, with this long left" is the AuthenticationService's arithmetic —
   * the same arithmetic that refuses an attempt, so that the panel cannot come to disagree with the
   * login screen about who is locked out.
   */
  public AccountSummary(
      String name,
      Role role,
      Optional<PasswordStrength> passwordStrength,
      Optional<Locale> languagePreference) {
    this(name, role, passwordStrength, languagePreference, Optional.empty());
  }

  /** The same Account, with what the service's clock made of the refusal the store holds. */
  public AccountSummary lockedFor(Optional<Duration> remaining) {
    return new AccountSummary(name, role, passwordStrength, languagePreference, remaining);
  }

  /** Whether this Account is waiting for somebody to give it a password of their own choosing. */
  public boolean isAwaitingEnrolment() {
    return passwordStrength.isEmpty();
  }
}
