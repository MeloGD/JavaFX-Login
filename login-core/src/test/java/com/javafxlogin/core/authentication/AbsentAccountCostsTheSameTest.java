package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import java.nio.file.Path;
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
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void anAttemptAgainstANonexistentAccountCostsTheSameAsAgainstARealOne() {
    warmUp();

    long[] realAccountSamples = new long[MEASURED_ATTEMPTS];
    long[] absentAccountSamples = new long[MEASURED_ATTEMPTS];

    // Interleaved rather than measured in two phases: this machine builds several projects at
    // once, and load that drifts between one phase and the next would read as a difference
    // between the two branches. Alternating makes any such drift hit both equally.
    for (int i = 0; i < MEASURED_ATTEMPTS; i++) {
      realAccountSamples[i] = nanosToRefuse(NAME);
      absentAccountSamples[i] = nanosToRefuse("nobody.here");
    }

    long realAccount = medianOf(realAccountSamples);
    long absentAccount = medianOf(absentAccountSamples);

    double difference = Math.abs(realAccount - absentAccount);
    double larger = Math.max(realAccount, absentAccount);
    assertTrue(
        difference / larger < TOLERATED_RELATIVE_DIFFERENCE,
        () ->
            "wrong password against a real Account took "
                + realAccount
                + " ns, against an absent one "
                + absentAccount
                + " ns — the difference is large enough to name which Accounts exist");
  }

  private void warmUp() {
    for (int i = 0; i < WARMUP_ATTEMPTS; i++) {
      nanosToRefuse(NAME);
      nanosToRefuse("nobody.here");
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
