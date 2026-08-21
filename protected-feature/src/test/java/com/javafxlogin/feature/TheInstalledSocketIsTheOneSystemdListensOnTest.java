package com.javafxlogin.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The one thing the two halves of a packaged installation have to agree on, held against the file
 * that decides it.
 *
 * <p>The socket's path is not negotiated and cannot be: systemd creates it before anything runs,
 * and the client has to know where to connect without asking anybody. If the two ever drift apart
 * the product still builds, still installs and still starts — and the person in front of it is told
 * the AuthenticationService is not running, because under socket activation a path nobody listens
 * on and a service nobody started are the same silence. That is the failure this test exists for.
 */
class TheInstalledSocketIsTheOneSystemdListensOnTest {

  private static final String LISTEN_STREAM = "ListenStream=";

  @Test
  void theHostProductConnectsWhereTheSocketUnitListens() {
    assertEquals(
        Path.of(listenStreamOfTheShippedSocketUnit()),
        ProtectedFeatureApplication.installedSocket(),
        "the shipped .socket unit and the packaged application name different sockets");
  }

  private static String listenStreamOfTheShippedSocketUnit() {
    Path unit = installerDirectory().resolve("javafx-login-authd.socket");
    try {
      return Files.readAllLines(unit).stream()
          .map(String::strip)
          .filter(line -> line.startsWith(LISTEN_STREAM))
          .map(line -> line.substring(LISTEN_STREAM.length()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(unit + " declares no " + LISTEN_STREAM));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Walks up from wherever the suite was started until the shipped installer is found. */
  private static Path installerDirectory() {
    for (Path directory = Path.of("").toAbsolutePath();
        directory != null;
        directory = directory.getParent()) {
      Path installer = directory.resolve("installer").resolve("linux");
      if (Files.isDirectory(installer)) {
        return installer;
      }
    }
    throw new IllegalStateException("installer/linux is not in any directory above this one");
  }
}
