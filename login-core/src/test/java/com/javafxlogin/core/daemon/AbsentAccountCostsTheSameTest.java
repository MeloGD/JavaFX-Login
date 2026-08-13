package com.javafxlogin.core.daemon;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A real Account and an absent one may differ by half the larger median, and no more. */
    private static final double TOLERATED_RELATIVE_DIFFERENCE = 0.5;

    @TempDir
    Path directory;

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

        long realAccount = medianNanos(NAME);
        long absentAccount = medianNanos("nobody.here");

        double difference = Math.abs(realAccount - absentAccount);
        double larger = Math.max(realAccount, absentAccount);
        assertTrue(
                difference / larger < TOLERATED_RELATIVE_DIFFERENCE,
                () -> "wrong password against a real Account took " + realAccount
                        + " ns, against an absent one " + absentAccount
                        + " ns — the difference is large enough to name which Accounts exist");
    }

    private void warmUp() {
        for (int i = 0; i < WARMUP_ATTEMPTS; i++) {
            harness.send(new Authenticate(NAME, "Wrong-Horse-9".toCharArray()));
            harness.send(new Authenticate("nobody.here", "Wrong-Horse-9".toCharArray()));
        }
    }

    private long medianNanos(String accountName) {
        long[] samples = new long[MEASURED_ATTEMPTS];
        for (int i = 0; i < samples.length; i++) {
            long started = System.nanoTime();
            harness.send(new Authenticate(accountName, "Wrong-Horse-9".toCharArray()));
            samples[i] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
