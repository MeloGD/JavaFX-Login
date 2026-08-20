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
 * <p>Every sentence comes out of the bundle for the language the panel is being drawn in, which is
 * the Administrator's own: what the store holds is a tag and a band, and neither of them is a word
 * in anybody's language until it reaches here.
 */
final class AccountText {

  private static final String ADMINISTRATOR = "account.role.administrator";
  private static final String OPERATOR = "account.role.operator";

  /** An Account nobody has enrolled against has no password, and so no band to report. */
  private static final String AWAITING_ENROLMENT = "account.band.awaiting-enrolment";

  private static final String WEAK = "account.band.weak";
  private static final String ACCEPTABLE = "account.band.acceptable";
  private static final String STRONG = "account.band.strong";

  /** An Account that has chosen none follows the machine's, which is not a choice of theirs. */
  private static final String NO_PREFERENCE = "account.language.none";

  private static final String NAMED_LANGUAGE = "account.language.named";

  private static final String LOCKED = "account.lockout.locked";

  /** Nothing to report, said as a mark rather than as a word, so the column reads at a glance. */
  private static final String NOT_LOCKED = "account.lockout.none";

  private AccountText() {}

  static String nameOf(InterfaceLanguage language, Role role) {
    return language.say(
        switch (role) {
          case ADMINISTRATOR -> ADMINISTRATOR;
          case OPERATOR -> OPERATOR;
        });
  }

  /**
   * The band, and never a number: the score behind it is discarded where it is estimated, precisely
   * so that no screen can rank the Accounts of a deployment by how cheap each one is to attack.
   *
   * <p>An Account awaiting enrolment is said to be waiting rather than shown a band. It reads as the
   * weakest one in the store, so that an unknown password can never read as a strong one, and
   * showing that here would have an Administrator nudging somebody about a password they have not
   * been given the chance to choose.
   */
  static String bandOf(InterfaceLanguage language, Optional<PasswordStrength> passwordStrength) {
    return language.say(passwordStrength.map(AccountText::keyOf).orElse(AWAITING_ENROLMENT));
  }

  private static String keyOf(PasswordStrength strength) {
    return switch (strength) {
      case WEAK -> WEAK;
      case ACCEPTABLE -> ACCEPTABLE;
      case STRONG -> STRONG;
    };
  }

  /**
   * The language this Account's holder reads, in that language, with the tag beside it so that two
   * variants of one language are told apart.
   *
   * <p>Named in itself rather than in the language the panel is drawn in, and deliberately: an
   * Administrator setting somebody's screens to a language is choosing something that person has to
   * recognise, and a build that ships no wording for it still names it the way its readers would.
   */
  static String preferenceOf(InterfaceLanguage language, Optional<Locale> languagePreference) {
    return languagePreference
        .map(
            preference ->
                language.say(
                    NAMED_LANGUAGE,
                    preference.getDisplayName(preference),
                    preference.toLanguageTag()))
        .orElseGet(() -> language.say(NO_PREFERENCE));
  }

  /**
   * Whether this Account is locked out, and for how long, in the same whole minutes the login
   * screen says it in — the two are the same wait and must not round differently.
   */
  static String lockoutOf(InterfaceLanguage language, Optional<Duration> lockedFor) {
    return lockedFor
        .map(remaining -> language.say(LOCKED, LockoutText.waitOf(language, remaining)))
        .orElseGet(() -> language.say(NOT_LOCKED));
  }
}
