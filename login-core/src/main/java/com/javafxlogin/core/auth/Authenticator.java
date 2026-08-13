package com.javafxlogin.core.auth;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;

import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The only component permitted to verify a password.
 *
 * <p>Not to be confused with the AuthenticationService, which is the privileged process this runs
 * inside. That distinction is settled vocabulary, not a preference: two different things sharing one
 * name would poison the glossary.
 *
 * <p>Hashes are Argon2id PHC strings, so the salt and the cost parameters travel with each hash.
 * Verification therefore reads its parameters from the stored hash rather than from this object's
 * configuration, and the configured parameters apply only to hashes this object produces.
 */
public final class Authenticator {

    private static final int SALT_LENGTH_IN_BYTES = 16;
    private static final int REFERENCE_PASSWORD_LENGTH = 32;

    private final Argon2Parameters parameters;
    private final Argon2Function function;

    /**
     * A hash of a password nobody holds, verified against whenever the named Account does not exist.
     * Doing the same Argon2id work either way is what stops a stopwatch from naming which Accounts
     * are real.
     */
    private final String absentAccountReferenceHash;

    public Authenticator(Argon2Parameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.function = Argon2Function.getInstance(
                parameters.memoryKib(),
                parameters.iterations(),
                parameters.parallelism(),
                parameters.outputLength(),
                Argon2.ID);
        this.absentAccountReferenceHash = hash(unguessablePassword());
    }

    /** The parameters new hashes are produced with. */
    public Argon2Parameters parameters() {
        return parameters;
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
     */
    public boolean verify(char[] password, String phcHash) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(phcHash, "phcHash");
        return Password.check(CharBuffer.wrap(password), phcHash)
                .with(Argon2Function.getInstanceFromHash(phcHash));
    }

    /**
     * Spends the cost of a verification against an Account that does not exist, and always fails.
     *
     * <p>Always returning false is the point: the caller cannot accidentally treat an absent Account
     * as authenticated, and the attempt costs what a real one costs.
     */
    public boolean verifyAgainstAbsentAccount(char[] password) {
        verify(password, absentAccountReferenceHash);
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
