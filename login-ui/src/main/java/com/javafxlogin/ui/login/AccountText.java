package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * What an Account looks like in the administration panel's list.
 *
 * <p>The service names things and never words them — a Role, a band, a language tag, a number of
 * milliseconds — and this is the other side of that bargain, in one place so that the list and
 * whatever else comes to show an Account cannot drift into wording the same fact two ways.
 *
 * <p>Every string here moves to a ResourceBundle when the interface learns a second language, which
 * is issue #13. The language column is the one that will read strangely until then: it says what
 * the CredentialStore holds, and nothing in this build writes it.
 */
final class AccountText {

  /** Said in the glossary's own words: there is one Administrator and one or more Operators. */
  private static final String ADMINISTRATOR = "Administración";

  private static final String OPERATOR = "Operador";

  private static final String WEAK = "Débil";
  private static final String ACCEPTABLE = "Aceptable";
  private static final String STRONG = "Fuerte";

  /** An Account that has chosen no language follows the machine's, which is not a choice of theirs. */
  private static final String NO_LANGUAGE = "El del sistema";

  private static final String LOCKED = "Bloqueada (%s)";

  /** Nothing to report, said as a mark rather than as a word, so the column reads at a glance. */
  private static final String NOT_LOCKED = "—";

  private AccountText() {}

  static String nameOf(Role role) {
    return switch (role) {
      case ADMINISTRATOR -> ADMINISTRATOR;
      case OPERATOR -> OPERATOR;
    };
  }

  /**
   * The band, and never a number: the score behind it is discarded where it is estimated, precisely
   * so that no screen can rank the Accounts of a deployment by how cheap each one is to attack.
   */
  static String bandOf(PasswordStrength strength) {
    return switch (strength) {
      case WEAK -> WEAK;
      case ACCEPTABLE -> ACCEPTABLE;
      case STRONG -> STRONG;
    };
  }

  /**
   * The language this Account's holder reads, in that language, with the tag beside it so that two
   * variants of one language are told apart.
   */
  static String languageOf(Optional<Locale> language) {
    return language
        .map(locale -> "%s (%s)".formatted(locale.getDisplayName(locale), locale.toLanguageTag()))
        .orElse(NO_LANGUAGE);
  }

  /**
   * Whether this Account is locked out, and for how long, in the same whole minutes the login
   * screen says it in — the two are the same wait and must not round differently.
   */
  static String lockoutOf(Optional<Duration> lockedFor) {
    return lockedFor.map(remaining -> LOCKED.formatted(LockoutText.waitOf(remaining))).orElse(NOT_LOCKED);
  }
}
