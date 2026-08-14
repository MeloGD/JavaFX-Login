package com.javafxlogin.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What an Account may be called.
 *
 * <p>ADR-0002 keeps the account list unreadable to an unprivileged attacker, so a predictable name
 * hands one entry of that list back for free. These rules are the ones that stop it.
 */
class AccountNamePolicyTest {

  /** A password that breaks no rule, so that only the name is under test here. */
  private static final String PASSWORD = "Correct-Horse-1";

  @TempDir Path directory;

  private final AccountPolicy policy = AccountPolicy.bundled();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "admin",
        "administrator",
        "root",
        "sa",
        "sysadmin",
        "superuser",
        "supervisor",
        "operator",
        "user",
        "owner",
        "test",
        "guest",
        "default"
      })
  void aPredictableNameIsRefused(String name) {
    assertEquals(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED), violationsOf(name));
  }

  /** The product's own name is as guessable as "admin" on a machine running the product. */
  @Test
  void theProductsOwnNameIsRefused() {
    assertTrue(violationsOf("JavaFX Login").contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "Root", "SysAdmin"})
  void matchingIsCaseInsensitive(String name) {
    assertTrue(violationsOf(name).contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin_", "a.d.m.i.n", "super-user", "sys admin"})
  void separatorsAreNormalisedAway(String name) {
    assertTrue(violationsOf(name).contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Adm1n", "r00t", "u53r", "gu3st", "6uest", "0perat0r"})
  void digitForLetterSubstitutionsAreSeenThrough(String name) {
    assertTrue(violationsOf(name).contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
  }

  /**
   * Matching is on the whole normalised name. A rule that matched substrings would refuse most of
   * the people this product is for — every name containing "sa" or "test" among them.
   */
  @ParameterizedTest
  @ValueSource(strings = {"rosalind.sanders", "wren.holloway", "ernesto.paredes", "testa.mercer"})
  void aNameThatMerelyContainsABlockedOneIsAccepted(String name) {
    assertEquals(List.of(), violationsOf(name));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "..."})
  void aNameWithNothingInItIsRefused(String name) {
    assertTrue(violationsOf(name).contains(PolicyViolation.ACCOUNT_NAME_BLANK));
  }

  /** The blocklist is a resource, so a deployment adds to it without a rebuild. */
  @Test
  void aDeploymentExtendsTheBlocklistWithAFile() throws IOException {
    Path extra = directory.resolve("blocked-account-names.txt");
    Files.writeString(extra, "# names this deployment refuses\nHollow Reach\n");
    AccountPolicy extended = AccountPolicy.bundledExtendedBy(extra);

    assertTrue(
        extended
            .assess("hollow.reach", PASSWORD.toCharArray())
            .violations()
            .contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
  }

  /** Extending is optional: the file not being there is the ordinary case, not a failure. */
  @Test
  void anAbsentExtensionFileLeavesTheBundledBlocklistStanding() {
    AccountPolicy extended = AccountPolicy.bundledExtendedBy(directory.resolve("absent.txt"));

    assertEquals(
        List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED),
        extended.assess("admin", PASSWORD.toCharArray()).violations());
    assertEquals(List.of(), extended.assess("wren.holloway", PASSWORD.toCharArray()).violations());
  }

  private List<PolicyViolation> violationsOf(String name) {
    return policy.assess(name, PASSWORD.toCharArray()).violations();
  }
}
