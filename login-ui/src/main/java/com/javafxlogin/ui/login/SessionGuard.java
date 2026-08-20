package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

/**
 * The component that reports Operator activity so an idle Session can expire.
 *
 * <p>It reports; it does not decide. Nothing here holds a countdown, works out when a Session is
 * due, or ends one — the AuthenticationService owns all three, and a guard that owned any of them
 * would be a guard a patched copy could talk out of them. What this class does is watch the window
 * the person is working in, say so when they do something, and ask when the service said the time
 * would be up.
 *
 * <p>It asks exactly once per countdown, at the moment the service said the Session would run out,
 * rather than polling: the service answers every report with how long is left, so the next question
 * is always scheduled from the service's own arithmetic. A guard that stops asking — because the
 * process it lives in died — takes its connection with it, and the Session ends without anything
 * here taking part.
 *
 * <p>Activity is coalesced, because a person moving a mouse produces hundreds of events a second
 * and the countdown only needs to know that the last few seconds were not idle. How much
 * coalescing is safe depends on the InactivityPeriod, which is the Administrator's to set and not
 * this class's to know — so the cadence follows what the service says the Session has left rather
 * than a constant that could quietly exceed it.
 */
final class SessionGuard {

  /**
   * The most a report is ever held back, however long the Session has. Well under any inactivity
   * period an Administrator would plausibly configure, so what it costs is precision nobody can
   * perceive.
   */
  private static final Duration AT_MOST = Duration.ofSeconds(20);

  /**
   * The share of what a Session has left that a report may be held back for. A quarter leaves
   * three more chances to report before the countdown could run out, so an Administrator who
   * configures a period shorter than {@link #AT_MOST} does not thereby expire someone who is
   * working.
   */
  private static final int A_QUARTER = 4;

  /**
   * Added to whatever the service says is left before asking again. Without it a guard whose
   * question arrived a millisecond early would be told a millisecond remained, and would ask again
   * a millisecond later, for as long as that took to stop being true.
   */
  private static final Duration SLACK = Duration.ofMillis(250);

  private final LoginGate gate;
  private final Session session;
  private final Consumer<String> whenTheSessionEnds;

  /**
   * One thread, so that a report and a question cannot be in flight at once, and a daemon one, so
   * that a guard nobody stopped cannot be what keeps the application running.
   */
  private final ScheduledExecutorService asking =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "session-guard");
            thread.setDaemon(true);
            return thread;
          });

  private volatile boolean watching = true;

  /** Far enough back that the first thing the person does is reported rather than swallowed. */
  private volatile long lastReportedAtNanos = System.nanoTime() - AT_MOST.toNanos();

  /** Reset from the service's own answer every time it gives one. */
  private volatile Duration reportNoMoreOftenThan = AT_MOST;

  private ScheduledFuture<?> nextQuestion;

  private SessionGuard(LoginGate gate, Session session, Consumer<String> whenTheSessionEnds) {
    this.gate = gate;
    this.session = session;
    this.whenTheSessionEnds = whenTheSessionEnds;
  }

  /**
   * Watches everything the person does inside {@code watched}, and says so.
   *
   * <p>The filters are on the node rather than on the scene so that a guard can be attached before
   * the window is shown: an event on anything inside it passes through here on its way down.
   *
   * @param whenTheSessionEnds handed the key of what to say rather than the sentence, on the JavaFX
   *     application thread: a Session that ends is said at the login screen the person is handed
   *     back to, and that screen may well be drawn in another language than this window was
   */
  static SessionGuard watching(
      Node watched, LoginGate gate, Session session, Consumer<String> whenTheSessionEnds) {
    SessionGuard guard = new SessionGuard(gate, session, whenTheSessionEnds);
    watched.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> guard.somethingHappened());
    watched.addEventFilter(MouseEvent.MOUSE_MOVED, event -> guard.somethingHappened());
    watched.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> guard.somethingHappened());
    watched.addEventFilter(ScrollEvent.SCROLL, event -> guard.somethingHappened());
    watched.addEventFilter(KeyEvent.KEY_PRESSED, event -> guard.somethingHappened());
    // The first question is asked rather than assumed: how long a Session has is the service's
    // arithmetic from the first moment, not something worked out here from a configured number.
    guard.ask(gate::stillLive);
    return guard;
  }

  /** Stops watching. A guard that has ended a Session has already stopped. */
  void stop() {
    watching = false;
    asking.shutdownNow();
  }

  /** On the JavaFX application thread: hundreds of times a second, and cheap every time. */
  private void somethingHappened() {
    long now = System.nanoTime();
    if (!watching || now - lastReportedAtNanos < reportNoMoreOftenThan.toNanos()) {
      return;
    }
    lastReportedAtNanos = now;
    ask(gate::reportActivity);
  }

  private void ask(Function<Session, SessionStatus> question) {
    if (!watching) {
      return;
    }
    submit(() -> answered(question.apply(session)));
  }

  private void answered(SessionStatus status) {
    switch (status) {
      case SessionContinues continues -> continues.expiresIn().ifPresent(this::itHasThisLeft);
      case SessionOver over -> ended(SessionEndedText.keyFor(over.reason()));
    }
  }

  private void itHasThisLeft(Duration expiresIn) {
    Duration aQuarterOfIt = expiresIn.dividedBy(A_QUARTER);
    reportNoMoreOftenThan = aQuarterOfIt.compareTo(AT_MOST) < 0 ? aQuarterOfIt : AT_MOST;
    askAgainWhenThatRunsOut(expiresIn);
  }

  /** Called on the guard's own thread, which is the only one that schedules. */
  private void askAgainWhenThatRunsOut(Duration expiresIn) {
    if (nextQuestion != null) {
      nextQuestion.cancel(false);
    }
    nextQuestion = schedule(() -> answered(gate.stillLive(session)), expiresIn.plus(SLACK));
  }

  private void ended(String saying) {
    if (!watching) {
      return;
    }
    watching = false;
    Platform.runLater(() -> whenTheSessionEnds.accept(saying));
    // Not stop(): shutting the executor down from a task running on it would interrupt the task
    // that is still finishing here. The window this guard was watching stops it once it is gone.
  }

  private void submit(Runnable question) {
    schedule(question, Duration.ZERO);
  }

  /**
   * Everything the guard says crosses a socket, so nothing here runs on the JavaFX application
   * thread. A service that cannot be reached ends the watching: whatever is behind that, the
   * Session cannot be relied on any more, and a person left looking at a window nobody is
   * checking is the one outcome this component exists to prevent.
   */
  private ScheduledFuture<?> schedule(Runnable question, Duration delay) {
    try {
      return asking.schedule(
          () -> {
            try {
              question.run();
            } catch (RuntimeException e) {
              // A service that cannot be reached, or a defect below it. Neither is an answer, and
              // the Session cannot be watched either way — which is worth saying, because a person
              // left in a window nobody is checking is what this component exists to prevent.
              ended(SessionEndedText.SERVICE_LOST);
            }
          },
          delay.toMillis(),
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // Stopped between being asked and asking. There is nothing left to find out.
      return null;
    }
  }
}
