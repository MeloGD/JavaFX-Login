package com.javafxlogin.core.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.auth.Argon2Parameters;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Vault on its own, with no service and no Session in sight: what wraps what, what opens it, and
 * what is not reachable from outside this package.
 *
 * <p>Stories 55 to 63 and ADR-0004. The one that everything else rests on is {@link
 * #aVaultDoesNotOpenForAPasswordThatDidNotWrapIt}: the Vault opens because a password derives a key,
 * so there is no boolean here for a patched build to flip.
 */
class SecretVaultTest {

  /** Cheap on purpose, as everywhere else in this suite: what is asserted is not the cost. */
  private static final Argon2Parameters CHEAP = new Argon2Parameters(256, 1, 1, 32);

  private static final String OPERATOR = "finch.mercer";
  private static final String ANOTHER_OPERATOR = "juno.vale";
  private static final char[] PASSWORD = "Another-Horse-2".toCharArray();
  private static final char[] REPLACEMENT = "A-Third-Horse-3".toCharArray();

  private static final String CONNECTION_STRING = "database.password";
  private static final char[] A_SECRET = "sa/8Xk!connect".toCharArray();

  @TempDir Path directory;

  // --- opening it ------------------------------------------------------------------------------

  /** Criterion 1, at this seam: a named secret goes in and the same one comes back out. */
  @Test
  void anOperatorKeepsASecretAndReadsItBack() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);

        assertArrayEquals(A_SECRET, unlocked.secretNamed(CONNECTION_STRING).orElseThrow());
      }
    }
  }

  /** Nothing kept under that name is empty rather than an error: a caller asks and is answered. */
  @Test
  void aNameNothingIsKeptUnderIsEmpty() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        assertEquals(Optional.empty(), unlocked.secretNamed("nothing.is.here"));
      }
    }
  }

  /**
   * The load-bearing assertion of ADR-0004. There is no check here that a patched build could remove:
   * the wrong password derives the wrong key, the key fails the tag, and no Vault comes back.
   */
  @Test
  void aVaultDoesNotOpenForAPasswordThatDidNotWrapIt() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);

      assertEquals(Optional.empty(), vault.unlockFor(OPERATOR, "Wrong-Horse-9".toCharArray()));
    }
  }

  /** An Account that has never enrolled holds no wrap, and a Vault it never opened does not open. */
  @Test
  void anAccountWithNoWrappedCopyOpensNothing() {
    try (SecretVault vault = open()) {
      assertFalse(vault.holdsAWrapFor(OPERATOR));
      assertEquals(Optional.empty(), vault.unlockFor(OPERATOR, PASSWORD));
    }
  }

  /** Story 55 and 63: the DataKey is one key, so what one Operator keeps another one reads. */
  @Test
  void everyOperatorReachesTheSameSecrets() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      vault.wrapFor(ANOTHER_OPERATOR, REPLACEMENT);

      try (UnlockedVault mine = unlock(vault, OPERATOR, PASSWORD)) {
        mine.keep(CONNECTION_STRING, A_SECRET);
      }
      try (UnlockedVault theirs = unlock(vault, ANOTHER_OPERATOR, REPLACEMENT)) {
        assertArrayEquals(A_SECRET, theirs.secretNamed(CONNECTION_STRING).orElseThrow());
      }
    }
  }

  // --- rewrapping and revoking ----------------------------------------------------------------

  /** Criterion 5: rotating a password is not destructive. */
  @Test
  void changingAPasswordRewrapsRatherThanLosingTheSecrets() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
        unlocked.rewrapUnder(REPLACEMENT);
      }

      assertEquals(Optional.empty(), vault.unlockFor(OPERATOR, PASSWORD), "the old one still opens it");
      try (UnlockedVault reopened = unlock(vault, OPERATOR, REPLACEMENT)) {
        assertArrayEquals(A_SECRET, reopened.secretNamed(CONNECTION_STRING).orElseThrow());
      }
    }
  }

  /** Criterion 6: revocation is real, and it is the wrapped copy that makes it so. */
  @Test
  void destroyingAWrapLeavesTheAccountWithNothingToOpen() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);

      assertTrue(vault.destroyWrapFor(OPERATOR));

      assertFalse(vault.holdsAWrapFor(OPERATOR));
      assertEquals(Optional.empty(), vault.unlockFor(OPERATOR, PASSWORD));
      assertFalse(vault.destroyWrapFor(OPERATOR), "there was nothing left to destroy");
    }
  }

  /** What is left after a revocation is still every other Operator's Vault. */
  @Test
  void destroyingOneWrapLeavesTheRestOfThemOpening() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      vault.wrapFor(ANOTHER_OPERATOR, REPLACEMENT);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
      }

      vault.destroyWrapFor(OPERATOR);

      try (UnlockedVault theirs = unlock(vault, ANOTHER_OPERATOR, REPLACEMENT)) {
        assertArrayEquals(A_SECRET, theirs.secretNamed(CONNECTION_STRING).orElseThrow());
      }
    }
  }

  /** A wrap written twice for one Account is one row, under a fresh salt each time. */
  @Test
  void wrappingAgainReplacesTheWrapAndSaltsItAfresh() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      String firstSalt = saltOf(OPERATOR);

      vault.wrapFor(OPERATOR, PASSWORD);

      assertEquals(1, wrapCount(), "one Account, one wrapped copy");
      assertNotEquals(firstSalt, saltOf(OPERATOR), "the salt was reused");
      assertTrue(vault.unlockFor(OPERATOR, PASSWORD).isPresent());
    }
  }

  // --- what the file holds --------------------------------------------------------------------

  /** The DataKey is never stored unwrapped, and the file is one place that can be checked. */
  @Test
  void nothingInTheFileIsAKeyInTheClear() throws IOException {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
      }
    }

    String hex = HexFormat.of().formatHex(Files.readAllBytes(vaultFile()));
    String machineKey = HexFormat.of().formatHex(machineKeyBytes());

    assertFalse(hex.contains(machineKey), "the MachineKey itself is in the Vault file");
    assertFalse(
        text(vaultFile()).contains(new String(A_SECRET)), "a secret is in the Vault in the clear");
  }

  /** Two secrets are under two keys, so the same plaintext twice is not the same ciphertext twice. */
  @Test
  void twoSecretsWithTheSameValueAreNotTheSameCiphertext() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep("one", A_SECRET);
        unlocked.keep("other", A_SECRET);
      }
    }

    assertNotEquals(ciphertextOf("one"), ciphertextOf("other"));
  }

  /**
   * A secret's key is derived for its name, so a row carried to another name does not decrypt. That
   * is the property that makes editing this file by hand useless rather than merely difficult.
   */
  @Test
  void aCiphertextMovedToAnotherNameDoesNotOpen() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
      }
    }

    copyTheRowUnderAnotherName(CONNECTION_STRING, "somebody.elses.name");

    try (SecretVault reopened = open();
        UnlockedVault unlocked = unlock(reopened, OPERATOR, PASSWORD)) {
      assertEquals(Optional.empty(), unlocked.secretNamed("somebody.elses.name"));
      assertArrayEquals(A_SECRET, unlocked.secretNamed(CONNECTION_STRING).orElseThrow());
    }
  }

  /** It survives the service stopping, which is the only reason it is a file at all. */
  @Test
  void everythingSurvivesTheVaultBeingClosedAndOpenedAgain() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
      }
    }

    try (SecretVault reopened = open();
        UnlockedVault unlocked = unlock(reopened, OPERATOR, PASSWORD)) {
      assertArrayEquals(A_SECRET, unlocked.secretNamed(CONNECTION_STRING).orElseThrow());
    }
  }

  /** Reopening makes no second DataKey: a Vault that rekeyed itself would lose every secret in it. */
  @Test
  void reopeningTheVaultKeepsTheOneDataKeyItAlreadyHad() {
    String wrapped;
    try (SecretVault vault = open()) {
      wrapped = machineWrapHex();
    }

    try (SecretVault reopened = open()) {
      assertEquals(wrapped, machineWrapHex());
    }
  }

  /**
   * Criterion 2, asserted rather than described. A ciphertext nothing can open is put in the file
   * beside a good one: unlocking still works, the good secret still reads, and the damaged one comes
   * back empty. A build that decrypted the Vault at unlock — the thing this criterion exists to
   * refuse — would fail at the unlock instead, and every one of these three assertions with it.
   */
  @Test
  void unlockingDecryptsNoSecretAtAll() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      try (UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD)) {
        unlocked.keep(CONNECTION_STRING, A_SECRET);
        unlocked.keep("damaged", A_SECRET);
      }
    }
    damageTheCiphertextOf("damaged");

    try (SecretVault reopened = open();
        UnlockedVault unlocked = unlock(reopened, OPERATOR, PASSWORD)) {
      assertArrayEquals(A_SECRET, unlocked.secretNamed(CONNECTION_STRING).orElseThrow());
      assertEquals(Optional.empty(), unlocked.secretNamed("damaged"));
    }
  }

  /**
   * The Vault carries a schema version of its own, separate from the CredentialStore's. ADR-0004
   * separated the two files because they change at different rates, and one number across both would
   * put them back on one schedule.
   */
  @Test
  void theVaultIsVersionedApartFromTheCredentialStore() {
    try (SecretVault vault = open()) {
      assertEquals(1, userVersionOfTheVault());
    }
  }

  /** A downgrade fails loudly rather than writing into a shape it does not understand. */
  @Test
  void aVaultFromALaterBuildIsRefused() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
    }
    setUserVersionOfTheVault(99);

    com.javafxlogin.core.store.SchemaTooNewException thrown =
        assertThrows(com.javafxlogin.core.store.SchemaTooNewException.class, this::open);

    assertEquals(99, thrown.foundVersion());
    assertEquals(1, thrown.understoodVersion());
  }

  // --- what is not reachable ------------------------------------------------------------------

  /**
   * Criterion 3, asserted against the shape rather than against a promise: nothing public in this
   * package answers with key material, and the type that holds it cannot be named from outside.
   *
   * <p>A test about types rather than behaviour, deliberately. The DataKey never being exposed is not
   * something a caller can be observed failing to do — it is something the API must make impossible,
   * and this is the assertion that a later commit adding a convenient getter has to argue with.
   */
  @Test
  void nothingPublicHandsOutKeyMaterial() {
    assertFalse(
        java.lang.reflect.Modifier.isPublic(DataKey.class.getModifiers()),
        "DataKey is public, so a signature elsewhere could name it");
    assertFalse(
        java.lang.reflect.Modifier.isPublic(MachineKey.class.getModifiers()),
        "MachineKey is public, so a signature elsewhere could name it");

    for (Class<?> exported : List.of(SecretVault.class, UnlockedVault.class)) {
      for (Method method : exported.getMethods()) {
        if (method.getDeclaringClass() != exported) {
          continue;
        }
        assertNotEquals(
            byte[].class,
            method.getReturnType(),
            () -> exported.getSimpleName() + "." + method.getName() + " answers with raw bytes");
      }
    }
  }

  /** A destroyed key is a destroyed key: the Vault a closed Session held opens nothing. */
  @Test
  void aClosedVaultHasNoKeyLeftToUse() {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
      UnlockedVault unlocked = unlock(vault, OPERATOR, PASSWORD);
      unlocked.keep(CONNECTION_STRING, A_SECRET);

      unlocked.close();

      assertThrows(IllegalStateException.class, () -> unlocked.secretNamed(CONNECTION_STRING));
    }
  }

  /**
   * The MachineKey is what provisions somebody with nobody present. A Vault whose key file has gone
   * says so rather than quietly making a second DataKey and stranding every secret in the file.
   */
  @Test
  void aVaultWhoseMachineKeyHasBeenReplacedRefusesToProvision() throws IOException {
    try (SecretVault vault = open()) {
      vault.wrapFor(OPERATOR, PASSWORD);
    }
    Files.write(machineKeyFile(), java.util.Base64.getEncoder().encode(new byte[32]));

    try (SecretVault reopened = open()) {
      assertThrows(VaultException.class, () -> reopened.wrapFor(ANOTHER_OPERATOR, REPLACEMENT));
      assertTrue(
          reopened.unlockFor(OPERATOR, PASSWORD).isPresent(),
          "an Operator's own copy has nothing to do with the machine's");
    }
  }

  // --- the plumbing this test needs -----------------------------------------------------------

  private SecretVault open() {
    return SecretVault.openOrCreate(vaultFile(), machineKeyFile(), CHEAP);
  }

  private static UnlockedVault unlock(SecretVault vault, String accountName, char[] password) {
    return vault.unlockFor(accountName, password).orElseThrow(() -> new AssertionError("no Vault"));
  }

  private Path vaultFile() {
    return directory.resolve("secrets.db");
  }

  private Path machineKeyFile() {
    return directory.resolve("secrets.key");
  }

  private byte[] machineKeyBytes() throws IOException {
    return java.util.Base64.getDecoder()
        .decode(Files.readString(machineKeyFile(), StandardCharsets.UTF_8).trim());
  }

  private static String text(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
  }

  private String saltOf(String accountName) {
    return HexFormat.of()
        .formatHex(
            bytes("SELECT kdf_salt FROM data_key_wraps WHERE account_name = '" + accountName + "'"));
  }

  private String ciphertextOf(String name) {
    return HexFormat.of()
        .formatHex(bytes("SELECT ciphertext FROM secrets WHERE name = '" + name + "'"));
  }

  private String machineWrapHex() {
    return HexFormat.of().formatHex(bytes("SELECT wrapped_data_key FROM machine_wrap"));
  }

  private int wrapCount() {
    return countOf("SELECT COUNT(*) FROM data_key_wraps");
  }

  private int userVersionOfTheVault() {
    return countOf("PRAGMA user_version");
  }

  private void setUserVersionOfTheVault(int version) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultFile());
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version = " + version);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private int countOf(String sql) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultFile());
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery(sql)) {
      return results.next() ? results.getInt(1) : -1;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] bytes(String sql) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultFile());
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery(sql)) {
      return results.next() ? results.getBytes(1) : new byte[0];
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Turns one secret into bytes no key opens, without touching any other row. */
  private void damageTheCiphertextOf(String name) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultFile());
        PreparedStatement statement =
            connection.prepareStatement("UPDATE secrets SET ciphertext = ? WHERE name = ?")) {
      statement.setBytes(1, new byte[48]);
      statement.setString(2, name);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void copyTheRowUnderAnotherName(String from, String to) {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vaultFile());
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO secrets (name, nonce, ciphertext, kept_at)"
                    + " SELECT ?, nonce, ciphertext, kept_at FROM secrets WHERE name = ?")) {
      statement.setString(1, to);
      statement.setString(2, from);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
