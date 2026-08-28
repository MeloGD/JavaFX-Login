package com.javafxlogin.core.authentication;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the files the package is made of are, for the tests that read them.
 *
 * <p>Three tests in this package read the shipped installer and had a copy of this walk each. They
 * are the tests of artifacts rather than of code — the unit files, the maintainer scripts, the
 * installer — and what they have in common is that the artifact is on disk rather than on the
 * classpath, so it has to be found from wherever the suite happened to be started.
 *
 * <p>{@code protected-feature} has a fourth copy. It is another Maven module and test code does not
 * cross between them, which is a copy this cannot retire.
 */
final class ShippedInstaller {

  private ShippedInstaller() {}

  /** Walks up from wherever the suite was started until the shipped installer is found. */
  static Path directory() {
    for (Path above = Path.of("").toAbsolutePath(); above != null; above = above.getParent()) {
      Path installer = above.resolve("installer").resolve("linux");
      if (Files.isDirectory(installer)) {
        return installer;
      }
    }
    throw new IllegalStateException("installer/linux is not in any directory above this one");
  }

  /** The checklists the units name in {@code Documentation=} and the package ships beside them. */
  static Path manualChecks() {
    return directory().getParent().getParent().resolve("docs").resolve("manual-checks");
  }
}
