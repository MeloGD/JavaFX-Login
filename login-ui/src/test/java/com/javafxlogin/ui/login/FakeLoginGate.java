package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionToken;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

  private volatile boolean reachable = true;
  private volatile boolean bootstrapNeeded;
  private volatile FirstRunOutcome nextOutcome = new AdministratorCreated();

  /** Whoever is added here is admitted with this password, and refused with any other. */
  FakeLoginGate admitting(String accountName, String password) {
    admissible.put(accountName, password);
    return this;
  }

  /** A machine with no Administrator, which is what puts the wizard on the screen. */
  FakeLoginGate needingItsAdministrator() {
    bootstrapNeeded = true;
    return this;
  }

  /** What the service answers the next attempt to create the Administrator with. */
  void answerTheWizardWith(FirstRunOutcome outcome) {
    nextOutcome = outcome;
  }

  /** Makes every later attempt fail the way an AuthenticationService that is not there fails. */
  void becomeUnreachable() {
    reachable = false;
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
  public Optional<Session> admit(String accountName, char[] password) {
    // Copied at once: the window blanks the array it handed over as soon as this returns.
    String offered = new String(password);
    attempts.add(accountName + "/" + offered);
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    if (!Objects.equals(admissible.get(accountName), offered)) {
      return Optional.empty();
    }
    return Optional.of(new Session(SessionToken.generate(new SecureRandom())));
  }

  @Override
  public boolean bootstrapNeeded() {
    if (!reachable) {
      throw new ServiceUnreachableException("There is no AuthenticationService in this test");
    }
    return bootstrapNeeded;
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
      bootstrapNeeded = false;
    }
    return nextOutcome;
  }
}
