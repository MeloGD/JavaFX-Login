package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seam 3's stand-in for the AuthenticationService: a gate that admits whoever it was told to, and
 * answers the first-run wizard however it was told to.
 *
 * <p>No service, no socket and no crypto. That is the point — a window that fails to close, or a
 * refusal that says too much, is then a failure of the window and cannot arrive dressed up as a
 * failure of the hashing.
 *
 * <p>It is asked from whichever thread the window makes its attempt on, so what it records is kept
 * thread-safe.
 */
final class FakeLoginGate implements LoginGate {

  private final Map<String, String> admissible = new ConcurrentHashMap<>();
  private final List<String> attempts = new CopyOnWriteArrayList<>();
  private final List<String> creations = new CopyOnWriteArrayList<>();

  private final AtomicInteger activityReports = new AtomicInteger();
  private final AtomicInteger questionsAboutTheSession = new AtomicInteger();
  private final AtomicInteger logouts = new AtomicInteger();

  private final List<String> enrolments = new CopyOnWriteArrayList<>();

  private volatile boolean reachable = true;
  private volatile boolean firstRunNeeded;
  private volatile boolean aSessionIsAlreadyLive;
  private volatile Duration lockedFor;
  private volatile FirstRunOutcome nextOutcome = new AdministratorCreated();
  private volatile EnrolmentOutcome nextEnrolmentOutcome = new Enrolled();
  private volatile Instant passwordResetAt;

  /** An Account the service says has no password yet, whatever is typed for it. */
  private volatile String awaitingEnrolment;

  /** What the service says about a Session from now on, until a test says otherwise. */
  private volatile SessionStatus status = new SessionContinues(Optional.of(Duration.ofMinutes(15)));

  /** Whoever is added here is admitted with this password, and refused with any other. */
  FakeLoginGate admitting(String accountName, String password) {
    admissible.put(accountName, password);
    return this;
  }

  /** A machine with no Administrator, which is what puts the wizard on the screen. */
  FakeLoginGate needingItsAdministrator() {
    firstRunNeeded = true;
    return this;
  }

  /** What the service answers the next attempt to create the Administrator with. */
  void answerTheWizardWith(FirstRunOutcome outcome) {
    nextOutcome = outcome;
  }

  /** A machine someone else is already working on, which is the one refusal that says so. */
  FakeLoginGate withASessionAlreadyLive() {
    aSessionIsAlreadyLive = true;
    return this;
  }

  /** An Account the service says has failed too often, and how long it has left to wait. */
  FakeLoginGate withAnAccountLockedFor(Duration remaining) {
    lockedFor = remaining;
    return this;
  }

  /** An Account the service says is waiting for somebody to give it a password. */
  FakeLoginGate awaitingEnrolment(String accountName) {
    awaitingEnrolment = accountName;
    return this;
  }

  /** What the service answers the next enrolment with. */
  void answerTheEnrolmentWith(EnrolmentOutcome outcome) {
    nextEnrolmentOutcome = outcome;
  }

  /** What the service says on the next admission about a reset the person was never told about. */
  void withAPasswordResetAt(Instant resetAt) {
    passwordResetAt = resetAt;
  }

  /** What the enrolment screen offered, as {@code name/secret/password}, in order. */
  List<String> enrolments() {
    return List.copyOf(enrolments);
  }

  /** Makes every later attempt fail the way an AuthenticationService that is not there fails. */
  void becomeUnreachable() {
    reachable = false;
  }

  /** How long the service says a Session has left, every time it is asked. */
  void sessionsLastFor(Duration remaining) {
    status = new SessionContinues(Optional.of(remaining));
  }

  /** A kiosk: the service says there is nothing to count down to. */
  void sessionsNeverExpire() {
    status = new SessionContinues(Optional.empty());
  }

  /** From the next question onwards, the service says the Session is over. */
  void theSessionEnds(SessionEndedReason reason) {
    status = new SessionOver(reason);
  }

  /** How many times the SessionGuard has said the Operator did something. */
  int activityReports() {
    return activityReports.get();
  }

  /** How many times the guard has asked whether the Session it watches is still there. */
  int questionsAboutTheSession() {
    return questionsAboutTheSession.get();
  }

  /** How many times a Session was ended deliberately. */
  int logouts() {
    return logouts.get();
  }

  /** What the window offered, as {@code name/password}, in the order it offered it. */
  List<String> attempts() {
    return List.copyOf(attempts);
  }

  /** What the wizard offered, as {@code name/password}, in the order it offered it. */
  List<String> creations() {
    return List.copyOf(creations);
  }

  @Override
  public Admission admit(String accountName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    attempts.add(accountName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (aSessionIsAlreadyLive) {
      return NotAdmitted.because(DeniedReason.SESSION_ALREADY_LIVE);
    }
    if (lockedFor != null) {
      return NotAdmitted.lockedFor(lockedFor);
    }
    if (accountName.equals(awaitingEnrolment)) {
      return NotAdmitted.because(DeniedReason.ENROLMENT_REQUIRED);
    }
    if (!Objects.equals(admissible.get(accountName), offered)) {
      return NotAdmitted.because(DeniedReason.AUTH_FAILED);
    }
    return new Admitted(
        new Session(SessionToken.generate(new SecureRandom())),
        Optional.ofNullable(passwordResetAt));
  }

  @Override
  public EnrolmentOutcome completeEnrolment(String accountName, char[] secret, char[] password) {
    // Copied at once: the window blanks both arrays it handed over as soon as this returns.
    String offeredSecret = new String(secret);
    String chosen = new String(password);
    enrolments.add(accountName + "/" + offeredSecret + "/" + chosen);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (nextEnrolmentOutcome instanceof Enrolled) {
      // As the real service does: the Account now has a password of its own, and the one thing it
      // was waiting for is over.
      admissible.put(accountName, chosen);
      awaitingEnrolment = null;
    }
    return nextEnrolmentOutcome;
  }

  @Override
  public SessionStatus reportActivity(Session session) {
    Objects.requireNonNull(session, "session");
    activityReports.incrementAndGet();
    return answerAboutTheSession();
  }

  @Override
  public SessionStatus stillLive(Session session) {
    Objects.requireNonNull(session, "session");
    questionsAboutTheSession.incrementAndGet();
    return answerAboutTheSession();
  }

  @Override
  public void logOut(Session session) {
    Objects.requireNonNull(session, "session");
    logouts.incrementAndGet();
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    status = new SessionOver(SessionEndedReason.NO_SUCH_SESSION);
  }

  private SessionStatus answerAboutTheSession() {
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    return status;
  }

  @Override
  public boolean firstRunNeeded() {
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    return firstRunNeeded;
  }

  @Override
  public FirstRunOutcome createAdministrator(String administratorName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    creations.add(administratorName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (nextOutcome instanceof AdministratorCreated) {
      // As the real service does: from here on this installation has its Administrator and the
      // wizard is over. The Account is deliberately not made admissible — the login screen asks
      // to act as an Operator, and the service refuses the Administrator there.
      firstRunNeeded = false;
    }
    return nextOutcome;
  }
}
