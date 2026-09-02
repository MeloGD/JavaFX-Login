package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * That {@code verify-on-a-machine.sh} and the two checklists it automates still say the same thing
 * about each other.
 *
 * <p>The script is a second copy of two documents, which is the standing risk in having it at all:
 * a box added to a checklist that the script never learns about, or a step the script keeps running
 * after the checklist stopped asking for it, and nothing anywhere says the two have parted. So each
 * step names the section it came from, each covered section says the script covers it, and this
 * holds the two halves of that together — the same way every other step in those documents already
 * names the test that covers it.
 *
 * <p>It reads both as text. What the script does to a machine is a machine's business, and none of
 * it is in here.
 */
class TheMachineVerifierAndTheChecklistsNameEachOtherTest {

  /**
   * How a step declares where it came from: {@code step packaging '1. A first installation'}.
   *
   * <p>Either quote will do. One of the sections has an apostrophe in its heading, and a script
   * naming it has to say so in double quotes.
   */
  private static final Pattern DECLARATION =
      Pattern.compile("^\\s*step\\s+(packaging|activation)\\s+(['\"])(.+?)\\2");

  /** How a checklist section says the script now covers it. */
  private static final String ANNOTATION = "_Covered by `verify-on-a-machine.sh`:_";

  /** The two checklists, under the word the script names each of them by. */
  private static final Map<String, String> CHECKLISTS =
      Map.of("packaging", "linux-packaging.md", "activation", "linux-service-activation.md");

  /** The script, as the repository ships it. */
  private static final String SCRIPT = "verify-on-a-machine.sh";

  private final List<String> script = lines(ShippedInstaller.directory().resolve(SCRIPT));

  @Test
  void everyStepTheScriptRunsNamesASectionOfAChecklistThatExists() {
    // A step naming a section nobody wrote is a step whose provenance is a guess, and the first
    // way these two part company is a section being renamed with nothing to say so.
    stepsByChecklist()
        .forEach(
            (checklist, sections) -> {
              Set<String> headings = bodiesOf(checklist).keySet();
              for (String section : sections) {
                assertTrue(
                    headings.contains(section),
                    () ->
                        SCRIPT
                            + " runs a step from \""
                            + section
                            + "\", which is not a section of "
                            + CHECKLISTS.get(checklist)
                            + ": "
                            + headings);
              }
            });
  }

  @Test
  void everySectionTheScriptCoversSaysSoWhereAPersonReadsIt() {
    // The person running the checklist by hand is the one who has to know which boxes a machine
    // has already answered. A step that covers a section silently costs them the afternoon this
    // script exists to give back.
    stepsByChecklist()
        .forEach(
            (checklist, sections) -> {
              Map<String, String> bodies = bodiesOf(checklist);
              for (String section : sections) {
                assertTrue(
                    bodies.getOrDefault(section, "").contains(ANNOTATION),
                    () ->
                        CHECKLISTS.get(checklist)
                            + " §"
                            + section
                            + " does not say that "
                            + SCRIPT
                            + " covers it, and the script runs a step from it");
              }
            });
  }

  @Test
  void noChecklistClaimsCoverTheScriptHasStoppedHaving() {
    // The other direction, and the worse one: a section that says a script is watching it when
    // nothing is. A box nobody ticks because a machine was said to have ticked it is a box that
    // is never checked again.
    Map<String, Set<String>> steps = stepsByChecklist();
    CHECKLISTS.forEach(
        (checklist, ignored) ->
            bodiesOf(checklist)
                .forEach(
                    (section, body) ->
                        assertFalse(
                            body.contains(ANNOTATION)
                                && !steps.getOrDefault(checklist, Set.of()).contains(section),
                            () ->
                                CHECKLISTS.get(checklist)
                                    + " §"
                                    + section
                                    + " says "
                                    + SCRIPT
                                    + " covers it, and the script runs no step from it")));
  }

  /** The sections each checklist's steps came from, in the order the script runs them. */
  private Map<String, Set<String>> stepsByChecklist() {
    Map<String, Set<String>> declared = new LinkedHashMap<>();
    for (String line : script) {
      Matcher declaration = DECLARATION.matcher(line);
      if (declaration.find()) {
        declared
            .computeIfAbsent(declaration.group(1), checklist -> new LinkedHashSet<>())
            .add(declaration.group(3));
      }
    }
    assertFalse(declared.isEmpty(), SCRIPT + " declares no steps at all");
    return declared;
  }

  /** Each {@code ## } section of a checklist against everything written under it. */
  private Map<String, String> bodiesOf(String checklist) {
    Map<String, StringBuilder> sections = new LinkedHashMap<>();
    StringBuilder current = new StringBuilder();
    for (String line : lines(ShippedInstaller.manualChecks().resolve(CHECKLISTS.get(checklist)))) {
      if (line.startsWith("## ")) {
        String heading = line.substring(3).trim();
        current = sections.computeIfAbsent(heading, named -> new StringBuilder());
      } else {
        current.append(line).append('\n');
      }
    }
    Map<String, String> bodies = new LinkedHashMap<>();
    sections.forEach((heading, body) -> bodies.put(heading, body.toString()));
    return bodies;
  }

  private static List<String> lines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException("there is no " + file, e);
    }
  }
}
