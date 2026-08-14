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

  /** Never logged. */
  @Override
  public String toString() {
    return "SessionToken[redacted]";
  }
}
