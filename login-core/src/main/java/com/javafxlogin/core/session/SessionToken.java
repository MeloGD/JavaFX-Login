package com.javafxlogin.core.session;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
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

    /** Reconstructs a token from bytes that arrived from elsewhere, such as over the wire. */
    public static SessionToken of(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != LENGTH_IN_BYTES) {
            throw new IllegalArgumentException(
                    "a SessionToken is " + LENGTH_IN_BYTES + " bytes, got " + value.length);
        }
        return new SessionToken(value.clone());
    }

    /** The token's bytes, as a copy so that a caller cannot mutate the token it was handed. */
    public byte[] copyOfBytes() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionToken token)) {
            return false;
        }
        return MessageDigest.isEqual(value, token.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    /** Never logged. */
    @Override
    public String toString() {
        return "SessionToken[redacted]";
    }
}
