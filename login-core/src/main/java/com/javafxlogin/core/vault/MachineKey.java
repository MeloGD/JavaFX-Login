package com.javafxlogin.core.vault;

import com.javafxlogin.core.store.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The key, readable only by the AuthenticationService, that holds the second wrapped copy of the
 * DataKey.
 *
 * <p>It is what lets the service provision an Operator, or rewrap after a password reset, with
 * nobody present — and without the DataKey ever being shown to the Administrator who asked for
 * either. ADR-0005 states plainly what that costs: whoever holds the Administrator password can
 * create an Operator, enrol it and read every secret, so the Administrator's exclusion from the Vault
 * is least privilege and not a boundary. The project chose that over a Vault that becomes
 * permanently unrecoverable the day the last Operator forgets their password.
 *
 * <p>The key lives beside the Vault at owner-only, made once and read on every start after that.
 * Nothing rotates it: a new one would leave the machine's copy of the DataKey unopenable, which is
 * the one copy that exists so that nobody has to be present.
 *
 * <p>It is not derived from anything about the machine, and by ADR-0006 it must not be: a file copied
 * to a replacement machine has to still be openable by whoever knows a password, because that is the
 * one situation a backup exists for. What protects it is the mode on the file and nothing else.
 */
final class MachineKey {

  private final byte[] material;

  private MachineKey(byte[] material) {
    this.material = material;
  }

  /**
   * The key in the file beside the Vault, creating it the first time.
   *
   * <p>The mode is reasserted on every start, not only set at creation. This is the one file that
   * unwraps the DataKey with no password at all, and its own protection is the mode and nothing
   * else, so an upgrade or a stray {@code chmod} that widened it must not survive a restart.
   *
   * @throws IOException if it can be neither read nor written, or is not one this build wrote
   */
  static MachineKey readOrCreate(Path keyFile, SecureRandom random) throws IOException {
    if (Files.exists(keyFile)) {
      OwnerOnlyFiles.createOrReassert(keyFile);
      return new MachineKey(read(keyFile));
    }
    byte[] material = new byte[AesGcm.KEY_BYTES];
    random.nextBytes(material);
    OwnerOnlyFiles.createNew(keyFile);
    Files.writeString(
        keyFile, Base64.getEncoder().encodeToString(material), StandardCharsets.UTF_8);
    return new MachineKey(material);
  }

  byte[] material() {
    return material;
  }

  @Override
  public String toString() {
    return "MachineKey[redacted]";
  }

  private static byte[] read(Path keyFile) throws IOException {
    try {
      byte[] material =
          Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
      if (material.length != AesGcm.KEY_BYTES) {
        throw new IOException("the key beside the Vault is not one this build wrote: " + keyFile);
      }
      return material;
    } catch (IllegalArgumentException e) {
      throw new IOException("the key beside the Vault is not readable: " + keyFile, e);
    }
  }
}
