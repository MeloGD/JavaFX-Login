package com.javafxlogin.core.audit;

import com.javafxlogin.core.store.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What links one AuthenticationEvent to the one before it: an HMAC over the entry and the chain
 * value of its predecessor.
 *
 * <p>Removing or editing an entry in the middle therefore breaks every entry after it, which is
 * what makes the record's own tampering visible. What it does <em>not</em> do is stop it: whoever
 * can read the key can recompute the whole chain. That is the point, stated exactly —
 * ADR-0005 leans on this log as the control against a compromised Administrator, and an
 * Administrator is an Account of this system, not an account of the machine. The key lives beside
 * the CredentialStore at owner-only, so an Administrator who is not also a MachineAdministrator
 * cannot read it, and a MachineAdministrator was never in the threat model (ADR-0001).
 *
 * <p>The key is made once, on the first event ever recorded, and read on every start after that.
 * Nothing rotates it: a new key would silently break every entry written under the old one, which
 * reads exactly like the tampering this exists to reveal.
 */
final class EventChain {

  private static final String ALGORITHM = "HmacSHA256";

  /** A full HMAC-SHA-256 key. Nothing here is derived from anything a person typed. */
  private static final int KEY_BYTES = 32;

  private final byte[] key;

  private EventChain(byte[] key) {
    this.key = key;
  }

  /**
   * The chain keyed by the file beside the store, creating that key the first time.
   *
   * @throws IOException if the key can be neither read nor written, or is not one this build wrote
   */
  static EventChain keyedBy(Path keyFile) throws IOException {
    if (Files.exists(keyFile)) {
      return new EventChain(read(keyFile));
    }
    byte[] key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key);
    OwnerOnlyFiles.createNew(keyFile);
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
    return new EventChain(key);
  }

  /**
   * The chain value of an entry that follows the given one.
   *
   * @param previous the chain value of the entry before it, or the empty string for the first entry
   *     ever recorded
   * @param entry the entry's own text, exactly as the file holds it
   */
  String after(String previous, String entry) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      mac.update(previous.getBytes(StandardCharsets.UTF_8));
      mac.update(entry.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(mac.doFinal());
    } catch (GeneralSecurityException e) {
      // HMAC-SHA-256 with a 32-byte key. Every Java runtime has it, and the key came from here.
      throw new IllegalStateException("this runtime cannot compute " + ALGORITHM, e);
    }
  }

  private static byte[] read(Path keyFile) throws IOException {
    try {
      byte[] key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8));
      if (key.length != KEY_BYTES) {
        throw new IOException("the key beside the record is not one this build wrote: " + keyFile);
      }
      return key;
    } catch (IllegalArgumentException e) {
      throw new IOException("the key beside the record is not readable: " + keyFile, e);
    }
  }
}
