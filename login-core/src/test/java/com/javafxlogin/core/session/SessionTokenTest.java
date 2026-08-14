package com.javafxlogin.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Granted;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SessionToken is opaque, 128 bits, and outlives neither the process nor a log line. */
class SessionTokenTest {

  @TempDir Path directory;

  @Test
  void isOneHundredAndTwentyEightBits() {
    SessionToken token = SessionToken.generate(new SecureRandom());

    assertEquals(16, token.copyOfBytes().length);
  }

  @Test
  void isDistinctEveryTimeItIsGenerated() {
    SecureRandom random = new SecureRandom();
    Set<String> values = new HashSet<>();

    for (int i = 0; i < 1000; i++) {
      values.add(HexFormat.of().formatHex(SessionToken.generate(random).copyOfBytes()));
    }

    assertEquals(1000, values.size());
  }

  @Test
  void handingOutTheBytesDoesNotHandOutTheToken() {
    SessionToken token = SessionToken.generate(new SecureRandom());

    byte[] taken = token.copyOfBytes();
    taken[0] ^= 0xFF;

    assertNotEquals(taken[0], token.copyOfBytes()[0], "copyOfBytes must return a copy");
  }

  /** Never logged: nothing that prints a token may print its value. */
  @Test
  void doesNotRevealItsValueWhenPrinted() {
    SessionToken token = SessionToken.generate(new SecureRandom());

    assertEquals("SessionToken[redacted]", token.toString());
  }

  /** Never written to disk: after a Granted, no file the service owns contains the token. */
  @Test
  void isNeverWrittenToDisk() throws IOException {
    Granted granted;
    try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
      harness.bootstrap("wren.holloway", "Correct-Horse-1");
      granted =
          (Granted)
              harness.send(
                  new Authenticate(
                      "wren.holloway", "Correct-Horse-1".toCharArray(), Role.ADMINISTRATOR));
    }

    byte[] token = granted.token().copyOfBytes();
    List<Path> files;
    try (Stream<Path> walked = Files.walk(directory)) {
      files = walked.filter(Files::isRegularFile).toList();
    }

    assertFalse(files.isEmpty(), "the service wrote nothing at all, so this proves nothing");
    for (Path file : files) {
      assertFalse(
          contains(Files.readAllBytes(file), token), () -> "the SessionToken was found in " + file);
    }
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }
}
