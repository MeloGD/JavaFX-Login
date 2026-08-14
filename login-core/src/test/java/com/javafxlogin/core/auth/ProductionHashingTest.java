package com.javafxlogin.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.authentication.AuthenticationService;
import com.javafxlogin.core.ipc.Bootstrap;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the production Argon2id parameters against the OWASP minimums.
 *
 * <p>This is the one place in the suite that pays the real hashing cost, and paying it a few times
 * is the point: everything else provisions Accounts with cheap parameters and reaches the identical
 * verification path, because the parameters travel inside the PHC string.
 */
class ProductionHashingTest {

  /** $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash> */
  private static final Pattern PHC =
      Pattern.compile("^\\$argon2id\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([^$]+)\\$([^$]+)$");

  @Test
  void theProductionParametersMeetTheOwaspMinimums() {
    Argon2Parameters production = Argon2Parameters.PRODUCTION;

    assertTrue(
        production.memoryKib() >= 19 * 1024,
        () -> "memory is " + production.memoryKib() + " KiB, below the 19 MiB minimum");
    assertTrue(
        production.iterations() >= 2,
        () -> "iterations is " + production.iterations() + ", below the minimum of 2");
    assertTrue(
        production.parallelism() >= 1,
        () -> "parallelism is " + production.parallelism() + ", below the minimum of 1");
  }

  @Test
  void aProductionHashIsAPhcStringCarryingItsSaltAndParameters() {
    Authenticator authenticator = new Authenticator(Argon2Parameters.PRODUCTION);

    String hash = authenticator.hash("Correct-Horse-1".toCharArray());

    Matcher matcher = PHC.matcher(hash);
    assertTrue(matcher.matches(), () -> "not a PHC string: " + hash);
    assertEquals("19", matcher.group(1), "Argon2 version");
    assertEquals(String.valueOf(Argon2Parameters.PRODUCTION.memoryKib()), matcher.group(2));
    assertEquals(String.valueOf(Argon2Parameters.PRODUCTION.iterations()), matcher.group(3));
    assertEquals(String.valueOf(Argon2Parameters.PRODUCTION.parallelism()), matcher.group(4));
    assertFalse(matcher.group(5).isEmpty(), "salt travels with the hash");
  }

  @Test
  void twoHashesOfTheSamePasswordDifferBecauseTheSaltIsRandom() {
    Authenticator authenticator = new Authenticator(Argon2Parameters.PRODUCTION);

    String first = authenticator.hash("Correct-Horse-1".toCharArray());
    String second = authenticator.hash("Correct-Horse-1".toCharArray());

    assertFalse(first.equals(second));
    assertTrue(authenticator.verify("Correct-Horse-1".toCharArray(), first));
    assertTrue(authenticator.verify("Correct-Horse-1".toCharArray(), second));
  }

  /**
   * The cheap parameters the rest of the suite uses reach the same verification code as the
   * production ones. If this ever stops holding, the suite stops testing what ships.
   */
  @Test
  void cheapAndProductionHashesAreVerifiedByTheSamePath() {
    Authenticator cheap = new Authenticator(new Argon2Parameters(256, 1, 1, 32));
    Authenticator production = new Authenticator(Argon2Parameters.PRODUCTION);

    String cheapHash = cheap.hash("Correct-Horse-1".toCharArray());
    String productionHash = production.hash("Correct-Horse-1".toCharArray());

    assertTrue(
        cheap.verify("Correct-Horse-1".toCharArray(), productionHash),
        "an Authenticator configured cheaply still verifies a production hash");
    assertTrue(
        production.verify("Correct-Horse-1".toCharArray(), cheapHash),
        "an Authenticator configured for production still verifies a cheap hash");
    assertFalse(cheap.verify("Wrong-Horse-9".toCharArray(), productionHash));
  }

  @Test
  void anAbsentAccountIsNeverVerified() {
    Authenticator authenticator = new Authenticator(new Argon2Parameters(256, 1, 1, 32));

    assertFalse(authenticator.verifyAgainstAbsentAccount("Correct-Horse-1".toCharArray()));
  }

  /**
   * The criterion is about what the service stores, not about what a constant says. Opening the
   * service the way production opens it must put a PHC string carrying the OWASP parameters into
   * the store — and must not put the password there in any form.
   */
  @Test
  void openingTheServiceAsProductionDoesStoresAnOwaspGradePhcHash(@TempDir Path directory) {
    try (AuthenticationService service =
        AuthenticationService.open(directory.resolve("credentials.db"))) {
      service.handle(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
    }

    String stored = storedHashOf(directory.resolve("credentials.db"), "wren.holloway");

    Matcher matcher = PHC.matcher(stored);
    assertTrue(matcher.matches(), () -> "the store does not hold a PHC string: " + stored);
    assertEquals(String.valueOf(Argon2Parameters.OWASP_MINIMUM_MEMORY_KIB), matcher.group(2));
    assertEquals(String.valueOf(Argon2Parameters.OWASP_MINIMUM_ITERATIONS), matcher.group(3));
    assertEquals(String.valueOf(Argon2Parameters.OWASP_MINIMUM_PARALLELISM), matcher.group(4));
    assertFalse(stored.contains("Correct-Horse-1"), "the password reached the store");
  }

  private static String storedHashOf(Path storeFile, String accountName) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + storeFile);
        PreparedStatement statement =
            connection.prepareStatement("SELECT password_hash FROM accounts WHERE name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next(), () -> "no Account named " + accountName + " was stored");
        return results.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
