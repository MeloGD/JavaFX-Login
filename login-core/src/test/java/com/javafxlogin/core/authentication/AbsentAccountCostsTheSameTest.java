package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.CreateAccount;
import com.javafxlogin.core.ipc.EnrolmentIssued;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: an attempt against a nonexistent Account must cost the same time as one against a real
 * Account, or the login screen leaks the account list through a stopwatch.
 *
 * <p>The parameters here are heavier than the rest of the suite uses on purpose: the assertion is
 * about where the time goes, so the Argon2id work has to dominate scheduling noise. They are still
 * far below production.
 */
class AbsentAccountCostsTheSameTest {

  private static final Argon2Parameters MEASURABLE = new Argon2Parameters(4096, 2, 1, 32);

  private static final String NAME = "wren.holloway";
  private static final String PASSWORD = "Correct-Horse-1";

  private static final int WARMUP_ATTEMPTS = 5;
  private static final int MEASURED_ATTEMPTS = 15;

  /**
   * A real Account and an absent one may differ by a quarter of the larger median, and no more.
   * Wide enough to absorb scheduling noise on a loaded machine, narrow enough that it fails long
   * before a difference becomes usable at a login screen.
   */
  private static final double TOLERATED_RELATIVE_DIFFERENCE = 0.25;

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithAnAdministrator() {
    harness = ServiceHarness.with(directory, MEASURABLE);
    harness.send(new Bootstrap(NAME, PASSWORD.toCharArray()));

    // Out of the way of the measurement, rather than switched off — a Lockout is a refusal made
    // before the Account has spent all its guesses, and this test is about what the branch before
    // that costs. Left at five, the real Account would be locked halfway through the warm-up and
    // every sample after it would be timing a refusal the absent name can never receive.
    harness.lockoutPolicyIs(
        WARMUP_ATTEMPTS + MEASURED_ATTEMPTS + 1, Duration.ofMinutes(15));
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void anAttemptAgainstANonexistentAccountCostsTheSameAsAgainstARealOne() {
    assertCostsTheSame(NAME, "nobody.here");
  }

  /**
   * And so does one against an Account nobody has enrolled yet. Its refusal says in words that it
   * exists, which story 30 asks for — but the words are the whole of what it gives away. A refusal
   * that came back in no time at all would name it as an Account before the answer did, and would
   * name it to whoever is guessing rather than to whoever holds the secret.
   */
  @Test
  void anAttemptAgainstAnAccountAwaitingEnrolmentCostsTheSameToo() {
    assertCostsTheSame(NAME, anAccountAwaitingEnrolment());
  }

  private void assertCostsTheSame(String oneName, String anotherName) {
    warmUp(oneName, anotherName);

    long[] oneSamples = new long[MEASURED_ATTEMPTS];
    long[] anotherSamples = new long[MEASURED_ATTEMPTS];

    // Interleaved rather than measured in two phases: this machine builds several projects at
    // once, and load that drifts between one phase and the next would read as a difference
    // between the two branches. Alternating makes any such drift hit both equally.
    for (int i = 0; i < MEASURED_ATTEMPTS; i++) {
      oneSamples[i] = nanosToRefuse(oneName);
      anotherSamples[i] = nanosToRefuse(anotherName);
    }

    long one = medianOf(oneSamples);
    long another = medianOf(anotherSamples);

    double difference = Math.abs(one - another);
    double larger = Math.max(one, another);
    assertTrue(
        difference / larger < TOLERATED_RELATIVE_DIFFERENCE,
        () ->
            "a refused attempt took "
                + one
                + " ns against "
                + oneName
                + " and "
                + another
                + " ns against "
                + anotherName
                + " — the difference is large enough to tell the two apart with a stopwatch");
  }

  /** An Account created the way an Administrator creates one, with the machine handed back. */
  private String anAccountAwaitingEnrolment() {
    String name = "finch.mercer";
    Response admitted =
        harness.send(new Authenticate(NAME, PASSWORD.toCharArray(), Role.ADMINISTRATOR));
    SessionToken administrator = assertInstanceOf(Granted.class, admitted).token();
    assertInstanceOf(
        EnrolmentIssued.class,
        harness.send(new CreateAccount(administrator, name, Role.OPERATOR)));
    harness.send(new Logout(administrator));
    return name;
  }

  private void warmUp(String oneName, String anotherName) {
    for (int i = 0; i < WARMUP_ATTEMPTS; i++) {
      nanosToRefuse(oneName);
      nanosToRefuse(anotherName);
    }
  }

  private long nanosToRefuse(String accountName) {
    long started = System.nanoTime();
    harness.send(new Authenticate(accountName, "Wrong-Horse-9".toCharArray(), Role.ADMINISTRATOR));
    return System.nanoTime() - started;
  }

  private static long medianOf(long[] samples) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }
}
