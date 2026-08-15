package com.javafxlogin.core.session;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * How long a Session may go without Operator activity before the AuthenticationService ends it, or
 * that expiry is switched off altogether.
 *
 * <p>Switched off is a value here rather than an absent one, because a kiosk deployment is a
 * decision an Administrator makes on purpose and not a setting nobody got round to. Nothing else in
 * the system has to remember that a null or a zero means "never".
 *
 * <p>One text form serves both the CredentialStore and the wire, so that what an Administrator
 * configured and what a client is told cannot drift apart: an ISO-8601 duration, or {@code
 * disabled}.
 */
public final class InactivityPeriod {

  /** The text form of expiry switched off, in the store and on the wire alike. */
  private static final String DISABLED = "disabled";

  /** What a deployment gets before an Administrator has said otherwise. */
  public static final InactivityPeriod DEFAULT = of(Duration.ofMinutes(15));

  private final Duration duration;

  private InactivityPeriod(Duration duration) {
    this.duration = duration;
  }

  /**
   * A period of inactivity after which a Session ends.
   *
   * @throws IllegalArgumentException if it is not strictly positive — a Session that expires the
   *     instant it is granted is not a configuration, it is a refusal written in the wrong place
   */
  public static InactivityPeriod of(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("An inactivity period lasts some time, not " + duration);
    }
    return new InactivityPeriod(duration);
  }

  /** Expiry switched off: the deployment is a kiosk and its Session ends some other way. */
  public static InactivityPeriod disabled() {
    return new InactivityPeriod(null);
  }

  /**
   * Reads back what {@link #text()} wrote.
   *
   * @throws IllegalArgumentException if it is not a period this build wrote
   */
  public static InactivityPeriod parse(String text) {
    Objects.requireNonNull(text, "text");
    if (DISABLED.equals(text)) {
      return disabled();
    }
    try {
      return of(Duration.parse(text));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Not an inactivity period: " + text, e);
    }
  }

  /** Whether expiry is switched off, in which case there is no countdown to reset or run out. */
  public boolean isDisabled() {
    return duration == null;
  }

  /** How long the Session may idle, or empty where expiry is switched off. */
  public Optional<Duration> expiresAfter() {
    return Optional.ofNullable(duration);
  }

  /** The one text form, written to the store and put on the wire. */
  public String text() {
    return isDisabled() ? DISABLED : duration.toString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof InactivityPeriod period && Objects.equals(duration, period.duration);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(duration);
  }

  @Override
  public String toString() {
    return "InactivityPeriod[" + text() + "]";
  }
}
