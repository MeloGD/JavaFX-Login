package com.javafxlogin.core.policy;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What an Account may be called.
 *
 * <p>Both the blocked names and the name being checked are put through the same canonical form
 * before they are compared, which is the whole mechanism: the list can then be written the way a
 * person types names, and every dressed-up spelling of an entry collapses onto it.
 */
final class AccountNameRules {

  private static final String SEPARATORS = "._-";

  private final Set<String> blockedNames;

  private AccountNameRules(Set<String> blockedNames) {
    this.blockedNames = blockedNames;
  }

  /** Canonicalises the list once, at start-up, so that a check is a single set lookup. */
  static AccountNameRules refusing(List<String> names) {
    return new AccountNameRules(
        names.stream()
            .map(AccountNameRules::canonicalFormOf)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toUnmodifiableSet()));
  }

  Set<PolicyViolation> violationsOf(String name) {
    String canonical = canonicalFormOf(name);
    if (canonical.isEmpty()) {
      return EnumSet.of(PolicyViolation.ACCOUNT_NAME_BLANK);
    }
    if (blockedNames.contains(canonical)) {
      return EnumSet.of(PolicyViolation.ACCOUNT_NAME_BLOCKED);
    }
    return EnumSet.noneOf(PolicyViolation.class);
  }

  /**
   * The form two names are compared in: composed by Unicode, lowercased, stripped of the separators
   * a person decorates a name with, and with every character that has a well-known digit or symbol
   * spelling folded onto that spelling.
   *
   * <p>Folding towards the digits rather than away from them is what makes the fold total. There is
   * no answer to whether {@code 1} means {@code i} or {@code l}, so the question is never asked:
   * both letters become {@code 1} and so does the digit, and {@code Adm1n}, {@code adm.in} and
   * {@code ADMIN} arrive at the same string.
   *
   * <p>The comparison that follows is on the whole form. A rule that matched substrings would
   * refuse most of the people this product is for, {@code rosalind.sanders} among them.
   */
  private static String canonicalFormOf(String name) {
    String normalised = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder canonical = new StringBuilder(normalised.length());
    for (int i = 0; i < normalised.length(); i++) {
      char character = normalised.charAt(i);
      if (Character.isWhitespace(character) || SEPARATORS.indexOf(character) >= 0) {
        continue;
      }
      canonical.append(folded(character));
    }
    return canonical.toString();
  }

  private static char folded(char character) {
    return switch (character) {
      case 'a', '@' -> '4';
      case 'b' -> '8';
      case 'e' -> '3';
      case 'g', '6' -> '9';
      case 'i', 'l', '!', '|' -> '1';
      case 'o' -> '0';
      case 's', '$' -> '5';
      case 't', '+' -> '7';
      case 'z' -> '2';
      default -> character;
    };
  }
}
