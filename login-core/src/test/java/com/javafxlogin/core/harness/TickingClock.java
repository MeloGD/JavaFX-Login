package com.javafxlogin.core.harness;

import com.javafxlogin.core.session.SessionClock;
import java.time.Duration;
import java.time.Instant;

/**
 * The two clocks a Session is timed against, moved by hand.
 *
 * <p>A suite cannot wait out an inactivity period, set the machine's clock, or suspend the machine
 * — and one that tried would be asserting about the operating system rather than about these
 * rules. Moving the two readings independently is what makes every case nameable: ordinary time
 * passing moves both, and a machine whose clock was set (or which came back from suspend) moves
 * only the one that can be moved.
 */
public final class TickingClock implements SessionClock {

  private long monotonicNanos;
  private Instant wallTime;

  private TickingClock(Instant wallTime) {
    // An arbitrary origin, as a monotonic clock has, and large enough that a test moving the wall
    // clock backwards does not need it to stay positive.
    this.monotonicNanos = Duration.ofDays(3).toNanos();
    this.wallTime = wallTime;
  }

  /** A clock reading the given wall time, with its monotonic count wherever it happens to be. */
  public static TickingClock startingAt(Instant wallTime) {
    return new TickingClock(wallTime);
  }

  /** Time passes: both clocks move by the same amount, which is what agreeing looks like. */
  public void passes(Duration elapsed) {
    monotonicNanos += elapsed.toNanos();
    wallTime = wallTime.plus(elapsed);
  }

  /**
   * The wall clock moves and the other one does not: someone set the machine's time, or the
   * machine came back from a suspend that the monotonic clock did not count. Nothing inside a JVM
   * can tell those two apart, which is why one method covers both.
   *
   * @param jump forwards, or backwards where it is negative
   */
  public void theWallClockJumps(Duration jump) {
    wallTime = wallTime.plus(jump);
  }

  @Override
  public long monotonicNanos() {
    return monotonicNanos;
  }

  @Override
  public Instant wallTime() {
    return wallTime;
  }
}
