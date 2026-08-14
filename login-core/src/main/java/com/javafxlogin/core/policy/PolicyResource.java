package com.javafxlogin.core.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * The word lists the policy is made of, kept out of the code so that a deployment can read them and
 * a rule can be changed without a build.
 *
 * <p>Both readers drop blank lines and lines beginning with {@code #}, so a list can explain itself
 * to whoever opens it.
 */
final class PolicyResource {

  private static final String COMMENT_PREFIX = "#";

  private PolicyResource() {}

  /** The entries of a list packaged with the application. */
  static List<String> linesOfBundledList(String resource) {
    try (InputStream stream = PolicyResource.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("policy list missing from the build: " + resource);
      }
      return entriesIn(new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
    } catch (IOException e) {
      throw new IllegalStateException("could not read the policy list " + resource, e);
    }
  }

  /**
   * The entries of a list a deployment wrote, or none if it wrote no such file.
   *
   * <p>Absence is the ordinary case rather than a failure: most deployments never extend a list,
   * and a service that refused to start because an optional file was missing would be a worse
   * outcome than the list it was going to add.
   *
   * <p>A file that is there and cannot be read is the opposite case and is not swallowed. It says a
   * deployment meant to refuse something, so continuing would apply a policy weaker than the one
   * that was configured — and would do it silently.
   */
  static List<String> linesOfFileIfPresent(Path file) {
    try {
      return entriesIn(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (NoSuchFileException e) {
      return List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the policy list " + file, e);
    }
  }

  private static List<String> entriesIn(List<String> lines) {
    return lines.stream()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith(COMMENT_PREFIX))
        .toList();
  }
}
