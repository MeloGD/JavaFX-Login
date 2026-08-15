package com.javafxlogin.core.session;

import java.time.Instant;

/**
 * The two clocks a Session is timed against.
 *
 * <p>Neither alone is enough. A monotonic clock cannot be moved, which is what makes it the measure
 * an attacker gains nothing by fiddling with — but on most platforms it excludes the time the
 * machine spent suspended, so a laptop closed for an hour would come back with the countdown where
 * it left it. A wall clock counts that hour, and can be set to any value by anyone who can change
 * the machine's time. Reading both is what lets the service expire on whichever says more time
 * passed, and notice when the two stop agreeing.
 *
 * <p>It is an interface because a suite cannot wait fifteen minutes or move the machine's clock,
 * and one that did would be testing the operating system rather than this code.
 */
public interface SessionClock {

  /**
   * A count of nanoseconds from an arbitrary origin that only ever moves forward. Meaningful only
   * as the difference between two readings.
   */
  long monotonicNanos();

  /** What the machine says the time is, which is what a person or a program can change. */
  Instant wallTime();

  /** The machine's own clocks, which is what the service runs against. */
  static SessionClock system() {
    return new SessionClock() {

      @Override
      public long monotonicNanos() {
        return System.nanoTime();
      }

      @Override
      public Instant wallTime() {
        return Instant.now();
      }
    };
  }
}
