package com.javafxlogin.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.javafxlogin.core.harness.ServiceHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store holds password hashes, so neither the account list nor the hashes may be readable by
 * the unprivileged account the graphical client runs as. That is the whole of ADR-0002.
 *
 * <p>The mode is asserted rather than the ownership: which user owns the file is a deployment
 * property the installer sets, while the mode is this code's responsibility.
 */
class StoreFilePermissionsTest {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private static final Set<PosixFilePermission> WORLD_READABLE =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

  @TempDir Path directory;

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void theStoreFileIsCreatedOwnerOnly() throws IOException {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertEquals(
        OWNER_ONLY,
        Files.getPosixFilePermissions(ServiceHarness.storeFileIn(directory)),
        "the CredentialStore must not be readable by the account the client runs as");
  }

  /**
   * The Vault and the key that holds the machine's copy of the DataKey are the service's files too,
   * and the same argument covers both: an unprivileged account that could read either would have the
   * ciphertexts and, in the second case, the means to unwrap the DataKey without any password.
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void theSecretVaultAndItsMachineKeyAreCreatedOwnerOnly() throws IOException {
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }

    assertEquals(
        OWNER_ONLY,
        Files.getPosixFilePermissions(ServiceHarness.vaultFileIn(directory)),
        "the SecretVault must not be readable by the account the client runs as");
    assertEquals(
        OWNER_ONLY,
        Files.getPosixFilePermissions(ServiceHarness.machineKeyFileIn(directory)),
        "the MachineKey must not be readable by the account the client runs as");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void thePermissionsOfTheVaultAreReassertedWhenItIsReopened() throws IOException {
    Path vaultFile = ServiceHarness.vaultFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }
    Files.setPosixFilePermissions(vaultFile, WORLD_READABLE);

    try (ServiceHarness reopened = ServiceHarness.cheap(directory)) {
      assertEquals(
          OWNER_ONLY,
          Files.getPosixFilePermissions(vaultFile),
          "an upgrade or a stray chmod must not leave the Vault readable");
    }
  }

  /**
   * The MachineKey unwraps the DataKey with no password at all, and nothing but the mode on the file
   * protects it. A stray chmod on that one must not survive a restart either.
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void thePermissionsOfTheMachineKeyAreReassertedWhenItIsReopened() throws IOException {
    Path machineKey = ServiceHarness.machineKeyFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }
    Files.setPosixFilePermissions(machineKey, WORLD_READABLE);

    try (ServiceHarness reopened = ServiceHarness.cheap(directory)) {
      assertEquals(
          OWNER_ONLY,
          Files.getPosixFilePermissions(machineKey),
          "an upgrade or a stray chmod must not leave the MachineKey readable");
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void thePermissionsAreReassertedWhenAnExistingStoreIsReopened() throws IOException {
    Path storeFile = ServiceHarness.storeFileIn(directory);
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
    }
    Files.setPosixFilePermissions(storeFile, WORLD_READABLE);

    try (ServiceHarness reopened = ServiceHarness.cheap(directory)) {
      assertEquals(
          OWNER_ONLY,
          Files.getPosixFilePermissions(storeFile),
          "an upgrade or a stray chmod must not leave the store readable");
    }
  }
}
