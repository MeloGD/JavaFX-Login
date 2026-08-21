package com.javafxlogin.core.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One Account as a Backup carries it: everything the CredentialStore holds about it that outlives
 * the machine it was held on.
 *
 * <p>It is not an {@link Account}, and the difference is the point. An Account is what the
 * AuthenticationService verifies a password against, so it carries the four things a login needs.
 * This is what survives a machine dying, so it carries the things a person would otherwise have to
 * be set up with again: which language they read, what they have failed, a Lockout they are still
 * serving, and a password reset they have not been told about yet.
 *
 * <p><b>There is no enrolment here, and that is deliberate.</b> An outstanding Enrolment is a secret
 * somebody is carrying to a machine that no longer exists, and resurrecting it on a replacement
 * would be handing that person a way in to a deployment nobody meant them to have yet.
 *
 * <p>The Account itself travels regardless, which is the difference between the Account and the
 * state it is in. An Operator whose password an Administrator took away yesterday is not transient —
 * they are a person with a name, a Role and a language, halfway through getting a new password — and
 * a Backup that dropped them because of when it was taken would be losing somebody to the timing of
 * a reset. So the hash is absent here where it was absent there, and a restore puts such an Account
 * back awaiting an enrolment nobody has been handed: the Administrator issues one, which is the
 * conversation they were going to have anyway, because the secret from the machine that died was
 * never going to work.
 *
 * <p>There is no wrapped copy of the DataKey either, because that is the SecretVault's and the
 * SecretVault is not in a Backup. A restored Operator can log in and cannot reach a secret until an
 * Administrator resets them and they enrol again — see ADR-0015.
 *
 * @param name the name typed at the login prompt, matched exactly
 * @param role the capability set attached to this Account
 * @param passwordHash the Argon2id hash as a PHC string, carrying its own salt and parameters, or
 *     empty where the Account was awaiting enrolment when the Backup was taken — the enrolment
 *     itself does not travel, so what is restored is an Account waiting for a secret nobody holds
 * @param passwordStrength the coarse band estimated when the password was chosen
 * @param createdAt when the Account came into existence, as the store wrote it
 * @param passwordResetAt when an Administrator last took this Account's password away, where its
 *     holder has not yet been told; empty otherwise. It travels because it is news that is owed and
 *     has not been delivered, and a restore that dropped it would be the machine dying making the
 *     notice disappear
 * @param languagePreference the language its holder reads the interface in, or empty where they
 *     have said nothing and whichever machine they are read on answers for them
 * @param failures what the Account has failed, and any Lockout it is still serving. It travels
 *     because ADR-0010 makes a Lockout a fact in the store rather than in memory, and a restore
 *     that cleared it would be a way to end one by restoring
 */
public record BackedUpAccount(
    String name,
    Role role,
    Optional<String> passwordHash,
    PasswordStrength passwordStrength,
    OffsetDateTime createdAt,
    Optional<Instant> passwordResetAt,
    Optional<Locale> languagePreference,
    FailedAuthentications failures) {

  /** An Account that holds a password of its own, which is most of them. */
  public BackedUpAccount(
      String name,
      Role role,
      String passwordHash,
      PasswordStrength passwordStrength,
      OffsetDateTime createdAt,
      Optional<Instant> passwordResetAt,
      Optional<Locale> languagePreference,
      FailedAuthentications failures) {
    this(
        name,
        role,
        Optional.of(passwordHash),
        passwordStrength,
        createdAt,
        passwordResetAt,
        languagePreference,
        failures);
  }

  public BackedUpAccount {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(passwordHash, "passwordHash");
    Objects.requireNonNull(passwordStrength, "passwordStrength");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(passwordResetAt, "passwordResetAt");
    Objects.requireNonNull(languagePreference, "languagePreference");
    Objects.requireNonNull(failures, "failures");
  }

  /** Whether this Account was waiting for somebody to give it a password when it was copied. */
  public boolean isAwaitingEnrolment() {
    return passwordHash.isEmpty();
  }

  /** Redacts the hash: an Account may be printed, its password material may not. */
  @Override
  public String toString() {
    return "BackedUpAccount[name="
        + name
        + ", role="
        + role
        + ", passwordHash="
        + (isAwaitingEnrolment() ? "awaiting enrolment" : "redacted")
        + "]";
  }
}
