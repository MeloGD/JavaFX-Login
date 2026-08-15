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

  /**
   * Reconstitutes the token the service issued, on the client side of the wire.
   *
   * <p>Only the service generates tokens; this is how the one it granted survives being carried
   * across the socket. The length is checked because a value of any other size did not come from
   * {@link #generate(SecureRandom)}.
   *
   * @throws IllegalArgumentException if the value is not {@link #LENGTH_IN_BYTES} long
   */
  public static SessionToken of(byte[] value) {
    Objects.requireNonNull(value, "value");
    if (value.length != LENGTH_IN_BYTES) {
      throw new IllegalArgumentException(
          "A SessionToken is " + LENGTH_IN_BYTES + " bytes, not " + value.length);
    }
    return new SessionToken(value.clone());
  }

  /** The token's bytes, as a copy so that a caller cannot mutate the token it was handed. */
  public byte[] copyOfBytes() {
    return value.clone();
  }

  /**
   * Compared in time that does not depend on how much of the value matched, because this is the
   * comparison that decides whether a caller holds the Session it claims to. {@code Arrays.equals}
   * stops at the first differing byte, which is a stopwatch's way of being told the first byte.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof SessionToken token && MessageDigest.isEqual(value, token.value);
  }

  /**
   * Derived from the whole value, as every hash code is. Nothing here is looked up in a hash table
   * — the service holds one Session at a time and compares tokens directly — so this exists to keep
   * the contract with {@link #equals} rather than to be fast.
   */
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
