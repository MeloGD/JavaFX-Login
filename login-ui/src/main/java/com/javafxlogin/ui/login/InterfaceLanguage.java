package com.javafxlogin.ui.login;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;

/**
 * The language the interface is drawn in, and every sentence it is drawn from.
 *
 * <p>It is chosen twice, and the order is the whole of issue #13. The login screen and the
 * first-run wizard have to be readable <em>before</em> anybody has authenticated, so they follow
 * the machine's own locale and offer a selector for when that locale is wrong. Only once somebody
 * has proved they hold an Account can that Account's LanguagePreference apply, because until then
 * this application does not know whose preference to use — a name in a box is not an Account.
 *
 * <p>Nothing in this package names a language. Which ones a build offers is {@code
 * languages.properties}, and what each of them says is a {@code messages_<tag>.properties} beside
 * it: adding a language is two files and no code, here or in the privileged process, which is what
 * the CredentialStore keeping a plain BCP 47 tag with no CHECK constraint was for.
 *
 * <p>A language this build ships no wording for is drawn in the first one offered rather than in a
 * mixture: a screen half-translated by a fallback is worse to read than a screen in one language
 * that is not yours, and the selector is one control away.
 */
final class InterfaceLanguage {

  private static final String BUNDLE = "com.javafxlogin.ui.login.messages";

  /**
   * The candidates end at the base bundle and never at the machine's own locale. What a person is
   * shown must depend on what this build ships and what they asked for, not on which locale the
   * JVM happens to have been started with.
   */
  private static final ResourceBundle.Control NO_FALLBACK =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private static final String OFFERED_FILE = "languages.properties";
  private static final String OFFERED = "offered";

  /** Read once from the jar this class came in: the list cannot change while a build runs. */
  private static final List<Locale> OFFERED_LANGUAGES = readTheOfferedLanguages();

  private final Locale locale;
  private final ResourceBundle wording;

  private InterfaceLanguage(Locale locale, ResourceBundle wording) {
    this.locale = locale;
    this.wording = wording;
  }

  /** Every language this build offers, in the order the selector lists them. */
  static List<Locale> offered() {
    return OFFERED_LANGUAGES;
  }

  /**
   * What the machine this application is running on reads, which is what somebody who has not
   * authenticated is shown.
   *
   * <p>The display locale rather than the format one: what is being chosen here is which words a
   * person is shown, and an operating system that separates the two is being taken at its word.
   */
  static InterfaceLanguage ofTheMachine() {
    return of(Locale.getDefault(Locale.Category.DISPLAY));
  }

  /**
   * The offered language closest to the one asked for, or the first offered where none is close.
   *
   * <p>Closest rather than equal, so that a machine set to Mexican Spanish is shown Spanish instead
   * of English: what is matched is the language, the way BCP 47 says to match it, and a build that
   * later ships a regional bundle beside the plain one starts being chosen without a change here.
   */
  static InterfaceLanguage of(Locale wanted) {
    Objects.requireNonNull(wanted, "wanted");
    Locale chosen = closestTo(wanted);
    return new InterfaceLanguage(chosen, ResourceBundle.getBundle(BUNDLE, chosen, NO_FALLBACK));
  }

  private static Locale closestTo(Locale wanted) {
    try {
      Locale found = Locale.lookup(LanguageRange.parse(wanted.toLanguageTag()), OFFERED_LANGUAGES);
      return found == null ? OFFERED_LANGUAGES.get(0) : found;
    } catch (IllegalArgumentException e) {
      // A locale that is not a language tag anybody can match against — a machine configured with
      // something this JVM could not make sense of. It is not a reason to fail to draw a window:
      // the first language offered is what a machine this build has no wording for is shown.
      return OFFERED_LANGUAGES.get(0);
    }
  }

  /** Which language this is, for the selector to sit on and for a formatter to be built from. */
  Locale locale() {
    return locale;
  }

  /**
   * The wording itself, for {@code FXMLLoader} to resolve the {@code %keys} in a screen with.
   *
   * <p>Handing the bundle over is what keeps the FXML free of Spanish and English alike: a screen
   * names what it wants to say, and which language that comes out in is decided here.
   */
  ResourceBundle wording() {
    return wording;
  }

  /**
   * One sentence, in this language.
   *
   * @throws java.util.MissingResourceException if this build has no wording under that key, which
   *     is a broken build rather than a machine to be forgiving about: every screen is loaded in
   *     every offered language by the tests, so a key that is missing anywhere fails there
   */
  String say(String key) {
    return wording.getString(key);
  }

  /**
   * One sentence with something of this deployment's in it — a name, a moment, a number of events.
   *
   * <p>Read by {@link MessageFormat} in this language, so that a number is grouped the way this
   * language groups numbers, and so that a sentence which changes with a count chooses in the
   * bundle rather than in the code that formats it. A message written here quotes its apostrophes,
   * which is MessageFormat's rule and is said again at the top of every bundle.
   */
  String say(String key, Object... arguments) {
    return new MessageFormat(wording.getString(key), locale).format(arguments);
  }

  /**
   * A moment as this machine writes moments for somebody reading this language.
   *
   * <p>The record's own format is ISO-8601 because it is read by tools; this one is read by a
   * person over their own shoulder, in the language they are being shown and the zone they are
   * sitting in.
   */
  DateTimeFormatter moments() {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault());
  }

  /**
   * A language named in itself, which is how a selector has to name one: somebody who cannot read
   * the language a screen is in has to be able to find their own in the list.
   */
  static String nameOf(Locale language) {
    String name = language.getDisplayLanguage(language);
    if (name.isEmpty()) {
      return language.toLanguageTag();
    }
    return name.substring(0, 1).toUpperCase(language) + name.substring(1);
  }

  private static List<Locale> readTheOfferedLanguages() {
    Properties languages = new Properties();
    try (InputStream file = InterfaceLanguage.class.getResourceAsStream(OFFERED_FILE)) {
      if (file == null) {
        throw new IllegalStateException(
            OFFERED_FILE + " is missing from the jar this class came in");
      }
      languages.load(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + OFFERED_FILE, e);
    }
    String offered = languages.getProperty(OFFERED, "");
    List<Locale> parsed = new ArrayList<>();
    for (String tag : offered.split(",")) {
      String trimmed = tag.trim();
      if (!trimmed.isEmpty()) {
        parsed.add(Locale.forLanguageTag(trimmed));
      }
    }
    if (parsed.isEmpty()) {
      throw new IllegalStateException(OFFERED_FILE + " offers no language at all");
    }
    return List.copyOf(parsed);
  }
}
