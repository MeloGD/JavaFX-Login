package com.javafxlogin.core.session;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * The opaque value that identifies a Session to the AuthenticationService.
 *
 * <p>It never outlives the process that issued it: nothing here writes it anywhere, no store column
 * holds it, and {@link #toString()} refuses to print it so that it cannot reach a log by accident.
 */
public final class SessionToken {

    /** 128 bits of service-generated randomness. */
    public static final int LENGTH_IN_BYTES = 16;

    private final byte[] value;

    private SessionToken(byte[] value) {
        this.value = value;
    }

    /** Issues a fresh token. */
    public static SessionToken generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] value = new byte[LENGTH_IN_BYTES];
        random.nextBytes(value);
        return new SessionToken(value);
    }

    /** The token's bytes, as a copy so that a caller cannot mutate the token it was handed. */
    public byte[] copyOfBytes() {
        return value.clone();
    }

    /** Never logged. */
    @Override
    public String toString() {
        return "SessionToken[redacted]";
    }
}
