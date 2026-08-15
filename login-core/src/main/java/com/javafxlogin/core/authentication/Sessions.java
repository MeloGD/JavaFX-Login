package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionClock;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The live Session, inside the privileged process, and every rule about when it is over.
 *
 * <p>There is at most one. A machine has one person at the keyboard, and the SecretVault a Session
 * reaches is shared by every Operator, so a second Session would be two people sharing one
 * machine's access to it. A second authentication while one is live is refused and the live one is
 * kept, which is the safer half of that choice: the person already working is not thrown out by
 * someone else typing a password.
 *
 * <p>Nothing here runs on a timer. A Session is evaluated when something asks about it, which is
 * either the SessionGuard reporting activity, the guard asking whether the Session it is watching
 * is still there, or a fresh authentication wanting to know whether the machine is free. A
 * privileged process therefore keeps no thread of its own for this, and a client that stops asking
 * simply expires — which is what makes a patched client unable to hold a Session open forever.
 *
 * <p>The one thing that does not wait to be asked is the connection going away: a Session is bound
 * to the connection it was granted on, and the kernel closes that connection when the client dies.
 * That is the whole of the crashed-client story — no heartbeat, and no Operator locked out.
 *
 * <p>Thread-safe on its own monitor, and the monitor is held only long enough to read two clocks.
 * It is not the service's monitor on purpose: the close listener runs on whichever thread noticed
 * the connection go, and must not queue behind an Argon2id hash to do it.
 */
public final class Sessions {

  /**
   * How far the wall clock may drift from the clock that cannot be moved before the Session is
   * ended.
   *
   * <p>This tolerance is not what makes moving the clock useless — the monotonic measure is, since
   * expiry runs against whichever clock says more time passed, and a clock set backwards therefore
   * buys nothing at all. It is only where the service stops treating a disagreement as ordinary
   * drift, and it is set well above what a time synchronisation corrects and below anything a
   * person would call walking away.
   */
  static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(1);

  private final SessionClock clock;

  private LiveSession live;

  /**
   * The Session that expired most recently, and why.
   *
   * <p>One slot, because there is one Session. It exists so that the client whose Session ran out
   * while it was not looking is told what happened when it next asks, rather than being told its
   * token means nothing — the difference between a person reading "your session timed out" and
   * reading nothing at all. Only expiry is remembered: a logout is not, because the client that
   * asked for it already knows.
   */
  private SessionToken lastExpired;

  private SessionEndedReason lastExpiredBecause;

  /**
   * The connection the close listener was last registered on.
   *
   * <p>One connection carries one Session at a time but may carry several in turn — someone logs
   * out and logs in again without the window ever going away — and one listener is enough for all
   * of them, because it asks when it runs whether the Session it would end is still that
   * connection's. Without this, a client that logged in and out all day would leave a listener
   * behind on its connection every time.
   */
  private ConnectionHandle watched;

  public Sessions(SessionClock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Records the Session just granted, and binds it to the connection it was granted on.
   *
   * <p>Any Session still held is replaced, which is why the caller has to have refused a second
   * authentication before reaching here.
   */
  public synchronized void open(
      SessionToken token, String accountName, Role role, ConnectionHandle connection) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(connection, "connection");

    live =
        new LiveSession(
            token, accountName, role, connection, clock.monotonicNanos(), clock.wallTime());
    if (watched != connection) {
      watched = connection;
      // Runs immediately if the client has already gone, which ends the Session before anything
      // can be done with it. That is the intended reading of a connection that closed mid-answer.
      connection.whenClosed(() -> endTheSessionOn(connection));
    }
  }

  /** Whether a Session is live. Expire any that is due first, or this answers about a dead one. */
  public synchronized boolean anyLive() {
    return live != null;
  }

  /**
   * Ends the live Session if the clocks say it is over, and answers why it ended.
   *
   * <p>Every request that touches a Session goes through here first, which is what makes expiry
   * something the service decides rather than something a client reports.
   */
  public synchronized Optional<ExpiredSession> expireIfDue(InactivityPeriod period) {
    Objects.requireNonNull(period, "period");
    if (live == null) {
      return Optional.empty();
    }
    Optional<SessionEndedReason> over = whyItIsOver(live, period);
    if (over.isEmpty()) {
      return Optional.empty();
    }
    ExpiredSession expired = new ExpiredSession(live.accountName, over.get());
    lastExpired = live.token;
    lastExpiredBecause = over.get();
    live = null;
    return Optional.of(expired);
  }

  /** The Operator did something: the countdown starts again from now, on both clocks. */
  public synchronized SessionOutcome reportActivity(
      SessionToken token, ConnectionHandle connection, InactivityPeriod period) {
    if (!namesTheLiveSession(token, connection)) {
      return endedFor(token);
    }
    live.monotonicAtLastActivity = clock.monotonicNanos();
    live.wallAtLastActivity = clock.wallTime();
    return liveWith(period, Duration.ZERO);
  }

  /** What the Session has left, without touching the countdown: asking is not activity. */
  public synchronized SessionOutcome statusOf(
      SessionToken token, ConnectionHandle connection, InactivityPeriod period) {
    if (!namesTheLiveSession(token, connection)) {
      return endedFor(token);
    }
    return liveWith(period, idleFor(live));
  }

  /** Ends the Session deliberately. Nothing is remembered: the client that asked already knows. */
  public synchronized void end(SessionToken token, ConnectionHandle connection) {
    if (namesTheLiveSession(token, connection)) {
      live = null;
    }
  }

  /**
   * Whether the token names the live Session <em>and</em> arrived on the connection that Session
   * was granted on. The connection is compared by identity, because one connection is one object
   * for as long as it exists, and a token replayed from another connection is not this Session.
   */
  private boolean namesTheLiveSession(SessionToken token, ConnectionHandle connection) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(connection, "connection");
    return live != null && live.connection == connection && live.token.equals(token);
  }

  private SessionOutcome endedFor(SessionToken token) {
    if (token.equals(lastExpired)) {
      return new SessionOutcome.Ended(lastExpiredBecause);
    }
    return new SessionOutcome.Ended(SessionEndedReason.NO_SUCH_SESSION);
  }

  private SessionOutcome liveWith(InactivityPeriod period, Duration idleFor) {
    return new SessionOutcome.Live(
        live.accountName, live.role, period.expiresAfter().map(after -> after.minus(idleFor)));
  }

  /**
   * The two clocks, read against the moment of the last activity.
   *
   * <p>A disagreement is settled before a countdown is read, because it is the anomaly: the service
   * can no longer say how long this Session sat idle, and a Session it cannot account for is one it
   * ends. Where the clocks agree, expiry runs against whichever of them says more time passed — the
   * monotonic one so that moving the machine's clock buys nothing, the wall clock so that time the
   * machine spent suspended still counts.
   */
  private Optional<SessionEndedReason> whyItIsOver(LiveSession session, InactivityPeriod period) {
    Optional<Duration> expiresAfter = period.expiresAfter();
    if (expiresAfter.isEmpty()) {
      // A kiosk. Nothing here ends the Session: a logout or the connection closing does.
      return Optional.empty();
    }
    Duration monotonic = monotonicSince(session);
    Duration wall = wallSince(session);
    if (wall.minus(monotonic).abs().compareTo(CLOCK_TOLERANCE) > 0) {
      return Optional.of(SessionEndedReason.CLOCK_JUMPED);
    }
    if (longerOf(monotonic, wall).compareTo(expiresAfter.get()) >= 0) {
      return Optional.of(SessionEndedReason.INACTIVITY);
    }
    return Optional.empty();
  }

  private Duration idleFor(LiveSession session) {
    return longerOf(monotonicSince(session), wallSince(session));
  }

  private Duration monotonicSince(LiveSession session) {
    return Duration.ofNanos(clock.monotonicNanos() - session.monotonicAtLastActivity);
  }

  private Duration wallSince(LiveSession session) {
    return Duration.between(session.wallAtLastActivity, clock.wallTime());
  }

  private static Duration longerOf(Duration one, Duration other) {
    return one.compareTo(other) >= 0 ? one : other;
  }

  private synchronized void endTheSessionOn(ConnectionHandle connection) {
    if (live != null && live.connection == connection) {
      live = null;
    }
  }

  /** The one Session, and the two clock readings its countdown is measured from. */
  private static final class LiveSession {

    private final SessionToken token;
    private final String accountName;
    private final Role role;
    private final ConnectionHandle connection;

    private long monotonicAtLastActivity;
    private Instant wallAtLastActivity;

    private LiveSession(
        SessionToken token,
        String accountName,
        Role role,
        ConnectionHandle connection,
        long monotonicAtLastActivity,
        Instant wallAtLastActivity) {
      this.token = token;
      this.accountName = accountName;
      this.role = role;
      this.connection = connection;
      this.monotonicAtLastActivity = monotonicAtLastActivity;
      this.wallAtLastActivity = wallAtLastActivity;
    }
  }
}
