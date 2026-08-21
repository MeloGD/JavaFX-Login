package com.javafxlogin.core.authentication;

import com.javafxlogin.core.session.SessionClock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The AuthenticationService stopping by itself once nobody is using it.
 *
 * <p>ADR-0002 asks for a privileged process that exists only while it is wanted: it starts when a
 * client connects and stops five minutes after the last one has gone. On Linux nothing is lost by
 * exiting — the socket belongs to systemd and stays listening, so the next connection starts the
 * service again and waits in the backlog while it boots. What that buys is that a machine nobody is
 * logging in to runs no privileged JVM at all, and that every counter this service keeps has to be
 * on disk to be worth anything, which is the reason {@link Lockouts} and {@link Enrolments} write
 * to the CredentialStore rather than remembering.
 *
 * <p>The service is in use while a Session is live <em>or</em> a client is still connected. A
 * connection with no Session behind it is a person at the login window who has not typed a password
 * yet, and exiting under them would drop the connection their next attempt goes over. Nothing can
 * hold the process open that way for longer than the client process itself lives: the kernel closes
 * the connection when the client dies, and the countdown starts from there.
 *
 * <p>Counted on the monotonic clock alone, unlike a Session and unlike a Lockout. This countdown
 * measures nothing but this process's own life and never outlives it, so the clock that cannot be
 * moved is the whole of what it needs — setting the machine's time neither ends a privileged
 * process early nor keeps one alive past its five minutes.
 *
 * <p>Thread-safe on its own monitor: the watching thread and whatever stops the process may both
 * arrive at once, and the stop action runs exactly once whichever does.
 */
public final class IdleShutdown implements AutoCloseable {

  /** Five minutes with nothing going on, as ADR-0002 sets it. */
  public static final Duration IDLE_PERIOD = Duration.ofMinutes(5);

  /**
   * How often the countdown is looked at.
   *
   * <p>Polling rather than being told, because being told would mean every path that opens or ends
   * a Session or a connection remembering to say so, and one that forgot would leave a privileged
   * JVM up for good. Asking costs two field reads a quarter of a minute, and the coarseness only
   * means the process lives up to that much past its five minutes.
   */
  static final Duration CHECK_INTERVAL = Duration.ofSeconds(15);

  private final SessionClock clock;
  private final BooleanSupplier inUse;
  private final Runnable stop;

  private long monotonicAtLastUse;
  private boolean stoppedTheService;
  private boolean watchingIsOver;
  private Thread watching;

  IdleShutdown(SessionClock clock, BooleanSupplier inUse, Runnable stop) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.inUse = Objects.requireNonNull(inUse, "inUse");
    this.stop = Objects.requireNonNull(stop, "stop");
    this.monotonicAtLastUse = clock.monotonicNanos();
  }

  /**
   * Starts watching the machine's own clock, and runs {@code stop} once the service has been idle
   * for {@link #IDLE_PERIOD}.
   *
   * @param inUse whether a Session is live or a client is connected
   * @param stop what to do about it, run once and on the watching thread
   */
  public static IdleShutdown startWatching(BooleanSupplier inUse, Runnable stop) {
    IdleShutdown shutdown = new IdleShutdown(SessionClock.system(), inUse, stop);
    shutdown.watch();
    return shutdown;
  }

  private synchronized void watch() {
    watching = Thread.ofVirtual().name("idle-shutdown").start(this::keepLooking);
  }

  private void keepLooking() {
    while (!watchingIsOver()) {
      try {
        Thread.sleep(CHECK_INTERVAL);
      } catch (InterruptedException e) {
        // The process is going down for a reason of its own, which is the one outcome this thread
        // exists to bring about. There is nothing left for it to watch.
        Thread.currentThread().interrupt();
        return;
      }
      reconsider();
    }
  }

  /**
   * Reads the clock once and decides whether the service is still wanted.
   *
   * <p>Visible to the suite so that five minutes can be moved rather than waited out.
   */
  synchronized void reconsider() {
    if (watchingIsOver) {
      return;
    }
    long now = clock.monotonicNanos();
    if (inUse.getAsBoolean()) {
      monotonicAtLastUse = now;
      return;
    }
    if (Duration.ofNanos(now - monotonicAtLastUse).compareTo(IDLE_PERIOD) < 0) {
      return;
    }
    stoppedTheService = true;
    watchingIsOver = true;
    stop.run();
  }

  /** Whether the idle period has run out and the service has been told to stop. */
  public synchronized boolean hasStopped() {
    return stoppedTheService;
  }

  private synchronized boolean watchingIsOver() {
    return watchingIsOver;
  }

  /** Stops watching. The service is going down by some other route, so the countdown is moot. */
  @Override
  public synchronized void close() {
    watchingIsOver = true;
    if (watching != null) {
      watching.interrupt();
      watching = null;
    }
  }
}
