package com.javafxlogin.core.account;

import java.util.Objects;
import java.util.Optional;

/**
 * A named identity that can authenticate against this system. Every Account holds exactly one Role.
 *
 * <p>The name is not a label: because ADR-0002 keeps the account list unreadable to an unprivileged
 * attacker, a predictable name donates an entry of that list back for free.
 *
 * @param name the name typed at the login prompt, matched exactly
 * @param role the capability set attached to this Account
 * @param passwordHash the Argon2id hash as a PHC string, carrying its own salt and parameters, or
 *     empty while the Account is awaiting enrolment — nobody has chosen a password for it yet, or
 *     an Administrator has taken the one it had away. An Account in that state authenticates
 *     against nothing: there is no hash to be right about, which is not the same as a hash nothing
 *     matches.
 * @param passwordStrength the coarse band estimated when the password was chosen, kept so that an
 *     Administrator can see which Accounts are worth nudging. An Account awaiting enrolment reads
 *     as the weakest band, because the band of a password nobody has chosen is not a fact to
 *     record and must not read as a strong one.
 */
public record Account(
    String name, Role role, Optional<String> passwordHash, PasswordStrength passwordStrength) {

  public Account {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(passwordStrength, "passwordStrength");
  }

  /** An Account with a password of its own, which is every Account anybody has ever enrolled. */
  public Account(String name, Role role, String passwordHash, PasswordStrength passwordStrength) {
    this(name, role, Optional.of(passwordHash), passwordStrength);
  }

  /** Whether this Account is waiting for somebody to give it a password of their own choosing. */
  public boolean isAwaitingEnrolment() {
    return passwordHash.isEmpty();
  }

  /** Redacts the hash: an Account may be printed, its password material may not. */
  @Override
  public String toString() {
    return "Account[name="
        + name
        + ", role="
        + role
        + ", passwordHash="
        + (isAwaitingEnrolment() ? "awaiting enrolment" : "redacted")
        + ", passwordStrength="
        + passwordStrength
        + "]";
  }
}
