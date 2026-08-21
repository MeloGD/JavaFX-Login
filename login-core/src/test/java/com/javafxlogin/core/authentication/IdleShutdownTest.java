package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.harness.TickingClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The service stopping by itself once nobody is using it, which is what keeps a privileged JVM from
 * sitting idle between logins.
 *
 * <p>Time is moved by hand here rather than waited out: a suite that waited five minutes for each
 * case would be measuring the operating system's timer. What systemd does with the process once it
 * exits — keeping the socket listening, and starting the service again on the next connection — is
 * not testable in a unit test and is covered by the checklist in
 * {@code docs/manual-checks/linux-service-activation.md}.
 */
class IdleShutdownTest {

  private static final Instant NOON = Instant.parse("2026-08-21T12:00:00Z");

  private final TickingClock clock = TickingClock.startingAt(NOON);
  private final AtomicBoolean somethingIsGoingOn = new AtomicBoolean(true);
  private final AtomicInteger stopped = new AtomicInteger();

  private final IdleShutdown shutdown =
      new IdleShutdown(clock, somethingIsGoingOn::get, stopped::incrementAndGet);

  @Test
  void stopsTheServiceOnceTheIdlePeriodHasPassedWithNothingGoingOn() {
    nothingIsGoingOn();

    timePasses(IdleShutdown.IDLE_PERIOD);

    assertEquals(1, stopped.get());
  }

  @Test
  void leavesTheServiceRunningWhileTheIdlePeriodHasNotRunOut() {
    nothingIsGoingOn();

    timePasses(IdleShutdown.IDLE_PERIOD.minusSeconds(1));

    assertEquals(0, stopped.get());
  }

  @Test
  void leavesTheServiceRunningForAsLongAsSomethingIsGoingOn() {
    // A login window left open all day holds its connection, and a Session lasts as long as the
    // Operator keeps working. Neither is a service sitting idle, and exiting under either would
    // drop a connection somebody is about to use.
    timePasses(IdleShutdown.IDLE_PERIOD.multipliedBy(10));

    assertEquals(0, stopped.get());
  }

  @Test
  void startsTheCountdownAgainWhenSomethingHappens() {
    nothingIsGoingOn();
    timePasses(IdleShutdown.IDLE_PERIOD.minusMinutes(1));

    somethingIsGoingOn.set(true);
    timePasses(Duration.ofSeconds(1));
    nothingIsGoingOn();

    timePasses(IdleShutdown.IDLE_PERIOD.minusMinutes(1));
    assertEquals(0, stopped.get());

    timePasses(Duration.ofMinutes(1));
    assertEquals(1, stopped.get());
  }

  @Test
  void stopsTheServiceOnlyOnce() {
    nothingIsGoingOn();

    timePasses(IdleShutdown.IDLE_PERIOD.multipliedBy(3));

    assertEquals(1, stopped.get());
  }

  @Test
  void countsOnTheClockThatCannotBeMoved() {
    // The countdown is a measure of this process's own life and nothing else, so it runs on the
    // monotonic clock alone. Setting the machine's time neither ends a privileged process early nor
    // keeps one alive, and neither does a suspend the monotonic clock did not count.
    nothingIsGoingOn();

    clock.theWallClockJumps(Duration.ofHours(3));
    shutdown.reconsider();

    assertEquals(0, stopped.get());
  }

  @Test
  void reportsWhetherItHasStoppedTheService() {
    assertFalse(shutdown.hasStopped());

    nothingIsGoingOn();
    timePasses(IdleShutdown.IDLE_PERIOD);

    assertTrue(shutdown.hasStopped());
  }

  /** Moves time in the steps the watching thread would have checked in, and checks at each one. */
  private void timePasses(Duration elapsed) {
    for (Duration passed = Duration.ZERO;
        passed.compareTo(elapsed) < 0;
        passed = passed.plus(IdleShutdown.CHECK_INTERVAL)) {
      clock.passes(shorterOf(IdleShutdown.CHECK_INTERVAL, elapsed.minus(passed)));
      shutdown.reconsider();
    }
  }

  private static Duration shorterOf(Duration one, Duration other) {
    return one.compareTo(other) <= 0 ? one : other;
  }

  private void nothingIsGoingOn() {
    somethingIsGoingOn.set(false);
  }
}
