package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The languages this build offers, held against the locale data the packaged runtime is linked
 * with.
 *
 * <p>A runtime that {@code jlink} trimmed carries the locale data it was asked for and no more, and
 * what is lost with it is the names languages call themselves. Without {@code es} in that list the
 * selector on the login screen offers "Spanish" rather than "Español" — in a product whose whole
 * position on this is ADR-0014, to a person who is choosing that language precisely because the
 * other one is not theirs. Nothing fails: the build is green, the package installs, and the screen
 * is wrong only where nobody who built it is looking.
 *
 * <p>This is the sole test in the suite that reads the packaging, and it is here rather than beside
 * the rest of it because the fact it is about is here: adding a language is a line in
 * {@code languages.properties}, and this is what makes that line reach the runtime as well.
 */
class TheTrimmedRuntimeCarriesEveryOfferedLanguageTest {

  private static final String LOCALES = "readonly RUNTIME_LOCALES='";

  @Test
  void theRuntimeIsLinkedWithDataForEveryLanguageTheLoginScreenOffers() {
    List<String> offered = InterfaceLanguage.offered().stream().map(Locale::getLanguage).toList();

    assertEquals(
        offered,
        localesTheBuildScriptKeeps(),
        "the packaged runtime carries locale data for a different set of languages than the one"
            + " the login screen offers, and the difference is a language named in English");
  }

  private static List<String> localesTheBuildScriptKeeps() {
    String script = read(buildScript());
    int start = script.indexOf(LOCALES);
    if (start < 0) {
      throw new IllegalStateException("build-deb.sh no longer declares " + LOCALES);
    }
    int from = start + LOCALES.length();
    return List.of(script.substring(from, script.indexOf('\'', from)).split(","));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Walks up from wherever the suite was started until the shipped installer is found. */
  private static Path buildScript() {
    for (Path directory = Path.of("").toAbsolutePath();
        directory != null;
        directory = directory.getParent()) {
      Path installer = directory.resolve("installer").resolve("linux");
      if (Files.isDirectory(installer)) {
        return installer.resolve("build-deb.sh");
      }
    }
    throw new IllegalStateException("installer/linux is not in any directory above this one");
  }
}
