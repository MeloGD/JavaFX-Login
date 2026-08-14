package com.javafxlogin.core.auth;

import com.password4j.Argon2Function;
import com.password4j.BadParametersException;
import com.password4j.Password;
import com.password4j.types.Argon2;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Turns a password into a hash, and checks a password against one.
 *
 * <p>It is the AuthenticationService that is permitted to verify a password — this is the component
 * it verifies with, and it exists only inside that process. The two names are deliberately
 * distinct: two different things sharing one would poison the glossary from the first commit.
 *
 * <p>Hashes are Argon2id PHC strings, so the salt and the cost parameters travel with each hash.
 * Verification therefore reads its parameters from the stored hash rather than from this object's
 * configuration, and the configured parameters apply only to hashes this object produces.
 */
public final class Authenticator {

  private static final int SALT_LENGTH_IN_BYTES = 16;
  private static final int REFERENCE_PASSWORD_LENGTH = 32;

  private final Argon2Function function;

  /**
   * A hash of a password nobody holds, verified against whenever the named Account does not exist.
   * Doing the same Argon2id work either way is what stops a stopwatch from naming which Accounts
   * are real.
   */
  private final String absentAccountReferenceHash;

  public Authenticator(Argon2Parameters parameters) {
    Objects.requireNonNull(parameters, "parameters");
    this.function =
        Argon2Function.getInstance(
            parameters.memoryKib(),
            parameters.iterations(),
            parameters.parallelism(),
            parameters.outputLength(),
            Argon2.ID);
    this.absentAccountReferenceHash = hash(unguessablePassword());
  }

  /** Hashes a password into a PHC string carrying its own random salt and cost parameters. */
  public String hash(char[] password) {
    Objects.requireNonNull(password, "password");
    return Password.hash(CharBuffer.wrap(password))
        .addRandomSalt(SALT_LENGTH_IN_BYTES)
        .with(function)
        .getResult();
  }

  /**
   * Verifies a password against a stored PHC hash, using the parameters recorded inside that hash
   * rather than this Authenticator's own.
   *
   * <p>A hash this build cannot read — damaged on disk, or written by something that is not
   * Argon2id — refuses at the same cost as an absent Account rather than throwing. An Account whose
   * hash is unreadable must be indistinguishable from one that does not exist: an exception
   * escaping here would reach the caller as a different outcome, and a bare {@code false} would
   * return in no time at all, both of which name the Account as real.
   */
  public boolean verify(char[] password, String phcHash) {
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(phcHash, "phcHash");
    try {
      return Password.check(CharBuffer.wrap(password), phcHash)
          .with(Argon2Function.getInstanceFromHash(phcHash));
    } catch (BadParametersException e) {
      return refuseAtEqualCost(password);
    }
  }

  /**
   * Spends the cost of a verification against an Account that does not exist, and always fails.
   *
   * <p>Always returning false is the point: the caller cannot accidentally treat an absent Account
   * as authenticated, and the attempt costs what a real one costs.
   *
   * <p>The equality is exact only while stored Accounts carry these same parameters, which is the
   * case for any Account this Authenticator hashed. Once parameters are raised and existing hashes
   * are re-made on next login, an Account still at the older cost is measurably cheaper than this —
   * until it logs in once. That window is the reason the rehash happens on login rather than
   * lazily, and it is the residue this method cannot close on its own: there is no Account to read
   * parameters from when the name matches nothing.
   */
  public boolean verifyAgainstAbsentAccount(char[] password) {
    Objects.requireNonNull(password, "password");
    return refuseAtEqualCost(password);
  }

  /**
   * Spends a verification's worth of Argon2id work against the reference hash, then refuses.
   *
   * <p>Deliberately not routed through {@link #verify}: verify falls back to this method when a
   * stored hash cannot be read, and going back the other way would turn an unreadable reference
   * hash into unbounded recursion. Reaching the library directly makes that impossible by shape
   * rather than by argument.
   */
  private boolean refuseAtEqualCost(char[] password) {
    Password.check(CharBuffer.wrap(password), absentAccountReferenceHash)
        .with(Argon2Function.getInstanceFromHash(absentAccountReferenceHash));
    return false;
  }

  private static char[] unguessablePassword() {
    SecureRandom random = new SecureRandom();
    char[] password = new char[REFERENCE_PASSWORD_LENGTH];
    for (int i = 0; i < password.length; i++) {
      password[i] = (char) ('!' + random.nextInt('~' - '!' + 1));
    }
    return password;
  }
}
