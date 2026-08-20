package com.javafxlogin.ui.login;

import com.javafxlogin.core.policy.PolicyViolation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns the rules the AccountPolicy named into sentences a person reads.
 *
 * <p>The privileged process names rules and never words them, so that the wording — and its
 * translation — belongs to whoever draws the window. This is that side of the bargain, and it is
 * exhaustive on purpose: a rule added over there that nobody worded over here would reach a person
 * as a blank refusal, which is exactly the failure that cannot be told apart from a bug.
 *
 * <p>What the switch names now is a key rather than a sentence, so that a rule added over there and
 * given no wording is a build that does not compile — and a rule worded in one language and not in
 * another is a test that does not pass.
 */
final class PolicyViolationText {

  private PolicyViolationText() {}

  /** Every broken rule as one paragraph, in the order the policy reports them. */
  static String paragraphFor(InterfaceLanguage language, List<PolicyViolation> violations) {
    return violations.stream()
        .map(violation -> sentenceFor(language, violation))
        .collect(Collectors.joining(" "));
  }

  static String sentenceFor(InterfaceLanguage language, PolicyViolation violation) {
    return language.say(
        switch (violation) {
          case ACCOUNT_NAME_BLANK -> "policy.account-name-blank";
          case ACCOUNT_NAME_BLOCKED -> "policy.account-name-blocked";
          case PASSWORD_TOO_SHORT -> "policy.password-too-short";
          case PASSWORD_TOO_LONG -> "policy.password-too-long";
          case PASSWORD_WITHOUT_UPPERCASE -> "policy.password-without-uppercase";
          case PASSWORD_WITHOUT_NUMBER -> "policy.password-without-number";
          case PASSWORD_WITHOUT_SPECIAL_CHARACTER -> "policy.password-without-special-character";
          case PASSWORD_BREACHED -> "policy.password-breached";
        });
  }
}
