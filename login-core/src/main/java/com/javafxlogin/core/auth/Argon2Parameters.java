package com.javafxlogin.core.auth;

/**
 * The Argon2id cost parameters a new hash is produced with.
 *
 * <p>These govern hashing only. Verification takes its parameters from the PHC string it is
 * checking, which is what lets a test provision an Account cheaply and still reach the identical
 * verification path, and what lets production parameters be raised later without invalidating
 * existing Accounts.
 *
 * @param memoryKib memory cost in kibibytes
 * @param iterations time cost
 * @param parallelism lanes
 * @param outputLength length of the derived hash in bytes
 */
public record Argon2Parameters(int memoryKib, int iterations, int parallelism, int outputLength) {

  /** OWASP's minimum memory cost for Argon2id: 19 MiB. */
  public static final int OWASP_MINIMUM_MEMORY_KIB = 19 * 1024;

  /** OWASP's minimum time cost for Argon2id. */
  public static final int OWASP_MINIMUM_ITERATIONS = 2;

  /** OWASP's minimum parallelism for Argon2id. */
  public static final int OWASP_MINIMUM_PARALLELISM = 1;

  /**
   * What ships. Sitting exactly at the OWASP minimums, and pinned by a test that asserts so —
   * raising them is a deliberate change to be made against that assertion.
   */
  public static final Argon2Parameters PRODUCTION =
      new Argon2Parameters(
          OWASP_MINIMUM_MEMORY_KIB, OWASP_MINIMUM_ITERATIONS, OWASP_MINIMUM_PARALLELISM, 32);

  public Argon2Parameters {
    if (memoryKib < 8 * parallelism) {
      throw new IllegalArgumentException(
          "Argon2 needs at least 8 KiB of memory per lane, got " + memoryKib);
    }
    if (iterations < 1) {
      throw new IllegalArgumentException("iterations must be at least 1, got " + iterations);
    }
    if (parallelism < 1) {
      throw new IllegalArgumentException("parallelism must be at least 1, got " + parallelism);
    }
    if (outputLength < 4) {
      throw new IllegalArgumentException(
          "outputLength must be at least 4 bytes, got " + outputLength);
    }
  }
}
