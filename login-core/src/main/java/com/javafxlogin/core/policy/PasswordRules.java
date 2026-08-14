package com.javafxlogin.core.policy;

import com.javafxlogin.core.account.PasswordStrength;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.gosimple.nbvcxz.Nbvcxz;
import me.gosimple.nbvcxz.resources.Configuration;
import me.gosimple.nbvcxz.resources.ConfigurationBuilder;
import org.passay.DefaultPasswordValidator;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.RuleResultDetail;
import org.passay.ValidationResult;
import org.passay.data.EnglishCharacterData;
import org.passay.rule.CharacterRule;
import org.passay.rule.LengthRule;
import org.passay.rule.Rule;

/**
 * What a password may be, and how strong it is thought to be.
 *
 * <p>The two are answered together because they are read from the same password and are the two
 * halves of what the person choosing it is owed: what they must change, and what they might want
 * to. Only the first refuses anything.
 *
 * <p>There is no periodic expiry here and there is nowhere to add one. Current OWASP guidance is
 * that forcing a rotation produces a worse password than the one it replaced.
 */
final class PasswordRules {

  private static final int MINIMUM_LENGTH = 12;
  private static final int MAXIMUM_LENGTH = 64;

  /** Below this many bits of estimated entropy a password is weak. */
  private static final double ACCEPTABLE_ENTROPY_BITS = 40;

  /** At or above this many bits it is strong. Between the two it is acceptable. */
  private static final double STRONG_ENTROPY_BITS = 60;

  /**
   * The estimator's reference data: the word lists packaged inside the nbvcxz artifact. Loaded once
   * per process because it is immutable and costs more than the estimate it serves. Nothing here
   * reaches the network — the lists ship inside the jar.
   */
  private static final Configuration ESTIMATOR_CONFIGURATION =
      new ConfigurationBuilder().createConfiguration();

  /**
   * The Passay error codes this policy knows how to explain. Written against Passay's own constants
   * rather than the strings behind them, so that a change of wording in the library is a compile
   * error here rather than a rule that quietly stops being reported.
   */
  private static final Map<String, PolicyViolation> VIOLATIONS_BY_ERROR_CODE =
      Map.of(
          LengthRule.ERROR_CODE_MIN,
          PolicyViolation.PASSWORD_TOO_SHORT,
          LengthRule.ERROR_CODE_MAX,
          PolicyViolation.PASSWORD_TOO_LONG,
          EnglishCharacterData.UpperCase.getErrorCode(),
          PolicyViolation.PASSWORD_WITHOUT_UPPERCASE,
          EnglishCharacterData.Digit.getErrorCode(),
          PolicyViolation.PASSWORD_WITHOUT_NUMBER,
          EnglishCharacterData.Special.getErrorCode(),
          PolicyViolation.PASSWORD_WITHOUT_SPECIAL_CHARACTER);

  private static final List<Rule> RULES =
      List.of(
          new LengthRule(MINIMUM_LENGTH, MAXIMUM_LENGTH),
          new CharacterRule(EnglishCharacterData.UpperCase, 1),
          new CharacterRule(EnglishCharacterData.Digit, 1),
          new CharacterRule(EnglishCharacterData.Special, 1));

  private final PasswordValidator characterAndLengthRules;
  private final Set<CharBuffer> breachedPasswords;

  private PasswordRules(Set<CharBuffer> breachedPasswords) {
    // Passay computes an entropy of its own, which this policy does not use and does not want:
    // the estimate the person is shown comes from nbvcxz, and two numbers disagreeing about one
    // password is worse than one number.
    this.characterAndLengthRules =
        new DefaultPasswordValidator(DefaultPasswordValidator.NO_ENTROPY_PROVIDER, RULES);
    this.breachedPasswords = breachedPasswords;
  }

  /**
   * Folds the breach list to lower case once, so that a check need not fold it every time.
   *
   * <p>Held as {@link CharBuffer}s rather than Strings so that a password can be looked up without
   * being turned into a String first: a CharBuffer compares and hashes by content, and the copy the
   * lookup wraps is one this class can zero afterwards.
   */
  static PasswordRules refusing(List<String> breachedPasswords) {
    return new PasswordRules(
        breachedPasswords.stream()
            .map(password -> CharBuffer.wrap(password.toLowerCase(Locale.ROOT)))
            .collect(Collectors.toUnmodifiableSet()));
  }

  Set<PolicyViolation> violationsOf(char[] password) {
    Set<PolicyViolation> violations = EnumSet.noneOf(PolicyViolation.class);
    // Passay takes a CharSequence, so the password is wrapped rather than copied into a String.
    ValidationResult result =
        characterAndLengthRules.validate(new PasswordData(CharBuffer.wrap(password)));
    for (RuleResultDetail detail : result.getDetails()) {
      violations.add(violationFor(detail));
    }
    if (isBreached(password)) {
      violations.add(PolicyViolation.PASSWORD_BREACHED);
    }
    return violations;
  }

  /**
   * The coarse band, from an estimate in bits that is neither returned nor stored.
   *
   * <p>The thresholds are this project's rather than the estimator's. nbvcxz's own top score begins
   * at 35 bits, which is a defensible floor for a login that answers over a network and is not one
   * for a store an attacker holds a copy of: that attacker guesses at their own pace, and Argon2id
   * raises the price of each guess without changing how many there are to make. Sixty bits is where
   * that arithmetic stops favouring them.
   *
   * <p>An empty password is not offered to the estimator: there is nothing there to estimate, and
   * the UI asks for an estimate on every keystroke including the first.
   */
  PasswordStrength strengthOf(char[] password) {
    if (password.length == 0) {
      return PasswordStrength.WEAK;
    }
    // nbvcxz has no overload that takes anything but a String. The String cannot be zeroed the way
    // a char[] can, so it is made here, handed straight to the estimator and referenced nowhere
    // afterwards. It is the only copy of a password this policy cannot clear.
    double bits = new Nbvcxz(ESTIMATOR_CONFIGURATION).estimate(new String(password)).getEntropy();
    if (bits < ACCEPTABLE_ENTROPY_BITS) {
      return PasswordStrength.WEAK;
    }
    return bits < STRONG_ENTROPY_BITS ? PasswordStrength.ACCEPTABLE : PasswordStrength.STRONG;
  }

  /**
   * Case-insensitively, on the whole password: a leaked password with its first letter capitalised
   * is the same guess an attacker already makes.
   */
  private boolean isBreached(char[] password) {
    char[] folded = new char[password.length];
    try {
      for (int i = 0; i < password.length; i++) {
        folded[i] = Character.toLowerCase(password[i]);
      }
      return breachedPasswords.contains(CharBuffer.wrap(folded));
    } finally {
      Arrays.fill(folded, '\0');
    }
  }

  /**
   * A detail carrying no code this policy knows is a defect in the rules above, not in the password
   * being checked: every rule constructed here has its code in the map. Failing loudly is the point
   * — the alternative is dropping the detail and accepting a password that broke a rule.
   */
  private static PolicyViolation violationFor(RuleResultDetail detail) {
    for (String code : detail.getErrorCodes()) {
      PolicyViolation violation = VIOLATIONS_BY_ERROR_CODE.get(code);
      if (violation != null) {
        return violation;
      }
    }
    throw new IllegalStateException("no reason is defined for the password rule " + detail);
  }
}
