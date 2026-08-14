package com.javafxlogin.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.PasswordStrength;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a password is allowed to be, and what the strength estimate is allowed to say about it. */
class PasswordPolicyTest {

  /** A name that breaks no rule, so that only the password is under test here. */
  private static final String NAME = "wren.holloway";

  private final AccountPolicy policy = AccountPolicy.bundled();

  @Test
  void aPasswordShorterThanTwelveCharactersIsRefused() {
    assertTrue(violationsOf("Correct-Ho1").contains(PolicyViolation.PASSWORD_TOO_SHORT));
  }

  @Test
  void aPasswordLongerThanSixtyFourCharactersIsRefused() {
    assertTrue(violationsOf("Aa1!" + "x".repeat(61)).contains(PolicyViolation.PASSWORD_TOO_LONG));
  }

  @Test
  void aPasswordAtEitherBoundaryIsAccepted() {
    assertEquals(List.of(), violationsOf("Correct-Hor1"));
    assertEquals(List.of(), violationsOf("Correct-Hor1" + "x".repeat(52)));
  }

  @Test
  void aPasswordWithoutAnUppercaseLetterIsRefused() {
    assertTrue(
        violationsOf("correct-horse-1").contains(PolicyViolation.PASSWORD_WITHOUT_UPPERCASE));
  }

  @Test
  void aPasswordWithoutANumberIsRefused() {
    assertTrue(violationsOf("Correct-Horse-x").contains(PolicyViolation.PASSWORD_WITHOUT_NUMBER));
  }

  @Test
  void aPasswordWithoutASpecialCharacterIsRefused() {
    assertTrue(
        violationsOf("CorrectHorse1x")
            .contains(PolicyViolation.PASSWORD_WITHOUT_SPECIAL_CHARACTER));
  }

  /** Every reason at once: a person retyping to satisfy one rule at a time gives up. */
  @Test
  void aPasswordIsToldEveryRuleItBreaks() {
    List<PolicyViolation> violations = violationsOf("short");

    assertTrue(
        violations.containsAll(
            List.of(
                PolicyViolation.PASSWORD_TOO_SHORT,
                PolicyViolation.PASSWORD_WITHOUT_UPPERCASE,
                PolicyViolation.PASSWORD_WITHOUT_NUMBER,
                PolicyViolation.PASSWORD_WITHOUT_SPECIAL_CHARACTER)),
        () -> "only reported " + violations);
  }

  /**
   * The list is bundled, so this refusal happens on a machine with no network at all. The password
   * below satisfies every other rule, which is what makes the breach list the reason.
   */
  @Test
  void aPasswordOnTheBundledBreachListIsRefused() {
    assertEquals(List.of(PolicyViolation.PASSWORD_BREACHED), violationsOf("Password123!"));
  }

  @Test
  void theBreachListSeesThroughCase() {
    assertTrue(violationsOf("PASSWORD123!").contains(PolicyViolation.PASSWORD_BREACHED));
  }

  @Test
  void aPasswordThatBreaksNoRuleIsAccepted() {
    assertEquals(List.of(), violationsOf("Correct-Horse-1"));
  }

  /** Informative, never blocking: a weak password that breaks no rule is still accepted. */
  @ParameterizedTest
  @ValueSource(strings = {"Tractor-Tractor-1", "Correct-Horse-1"})
  void aWeakEstimateDoesNotRefuseAPassword(String password) {
    assertEquals(List.of(), violationsOf(password));
  }

  @Test
  void aPredictablePasswordEstimatesWeak() {
    assertEquals(PasswordStrength.WEAK, strengthOf("Password123!"));
  }

  /** The middle band is reachable: an ordinary careful password is neither weak nor strong. */
  @Test
  void anOrdinaryPasswordEstimatesAcceptable() {
    assertEquals(PasswordStrength.ACCEPTABLE, strengthOf("Bramble-Quilt-58#"));
  }

  @Test
  void aLongUnpredictablePasswordEstimatesStrong() {
    assertEquals(PasswordStrength.STRONG, strengthOf("Tj7#vQm2!wLp4Zx9"));
  }

  /** Typing has to start somewhere, and the meter is asked for a band from the first keystroke. */
  @Test
  void anEmptyPasswordIsRefusedAndEstimatedWithoutFailing() {
    Assessment assessment = policy.assess(NAME, new char[0]);

    assertTrue(assessment.violations().contains(PolicyViolation.PASSWORD_TOO_SHORT));
    assertEquals(PasswordStrength.WEAK, assessment.strength());
  }

  /** The estimate is made for display, so it is made even when the password will be refused. */
  @Test
  void aRefusedPasswordIsStillEstimated() {
    Assessment assessment = policy.assess(NAME, "short".toCharArray());

    assertFalse(assessment.violations().isEmpty());
    assertEquals(PasswordStrength.WEAK, assessment.strength());
  }

  private List<PolicyViolation> violationsOf(String password) {
    return policy.assess(NAME, password.toCharArray()).violations();
  }

  private PasswordStrength strengthOf(String password) {
    return policy.assess(NAME, password.toCharArray()).strength();
  }
}
