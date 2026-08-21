package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.ServiceUnreachableReason;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.SessionEndedReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Seam 3, below the windows: the wording itself.
 *
 * <p>Issue #13's last criterion is that a missing key fails here rather than quietly at runtime, in
 * front of somebody who was reading a screen. Three things are checked to make that true. Every
 * offered language holds exactly the keys the base bundle holds, so a language cannot be shipped
 * half-translated. Every enum the AuthenticationService names — a broken rule, a Session that
 * ended, a refusal, a Role, a band — is worded in every one of them, so a constant added over there
 * and given no wording is caught here rather than reaching a person as a blank. And a key nothing
 * ships wording for throws, which is what the two above are asserting the absence of.
 */
class WordingTest {

  private static final String BASE = "messages.properties";

  private static final Locale ENGLISH = Locale.forLanguageTag("en");
  private static final Locale SPANISH = Locale.forLanguageTag("es");

  /** Criterion 6: both bundles exist, and this is where "complete" is given a meaning. */
  @Test
  void bothLanguagesThisProductShipsAreOffered() {
    assertTrue(
        InterfaceLanguage.offered().containsAll(List.of(ENGLISH, SPANISH)),
        () -> "the two languages this product ships are not both offered: "
            + InterfaceLanguage.offered());
  }

  /**
   * A language offered with no wording of its own is the one failure the tests below cannot see:
   * every one of them would read that language out of the base bundle, compare it against itself
   * and pass, while the people it was offered to were shown a language they did not ask for.
   *
   * <p>It is asserted here rather than left to {@link #fileFor}, so that the sentence a developer
   * reads names what is missing rather than a comparison that mysteriously holds.
   */
  @Test
  void everyOfferedLanguageHasWordingOfItsOwn() {
    List<Locale> offered = InterfaceLanguage.offered();

    for (Locale language : offered.subList(1, offered.size())) {
      assertNotNull(
          WordingTest.class.getResource(bundleFor(language)),
          () ->
              bundleFor(language)
                  + " is missing: "
                  + language.toLanguageTag()
                  + " is offered and this build has no wording for it");
    }
  }

  @Test
  void everyOfferedLanguageHoldsExactlyTheKeysTheBaseBundleHolds() {
    Set<String> expected = keysOf(BASE);

    for (Locale offered : InterfaceLanguage.offered()) {
      Set<String> keys = keysOf(fileFor(offered));

      assertEquals(
          expected,
          keys,
          () -> "the bundle for " + offered.toLanguageTag() + " does not say the same things");
    }
  }

  /** A key present but empty is a screen with a blank where a sentence should be. */
  @Test
  void nothingIsWordedAsNothing() {
    for (Locale offered : InterfaceLanguage.offered()) {
      Properties wording = read(fileFor(offered));

      wording.forEach(
          (key, sentence) ->
              assertFalse(
                  sentence.toString().isBlank(),
                  () -> key + " is blank in " + offered.toLanguageTag()));
    }
  }

  /**
   * A message that takes a name in one language takes it in every one of them. A translation that
   * dropped the {@code {0}} would show a sentence about an Account without saying which.
   */
  @Test
  void everyMessageTakesTheSameThingsInEveryLanguage() {
    Properties base = read(BASE);

    for (Locale offered : InterfaceLanguage.offered()) {
      Properties wording = read(fileFor(offered));

      base.forEach(
          (key, sentence) ->
              assertEquals(
                  argumentsOf(sentence.toString(), ENGLISH),
                  argumentsOf(wording.getProperty(key.toString()), offered),
                  () -> key + " takes different things in " + offered.toLanguageTag()));
    }
  }

  /** Every rule the AccountPolicy can name, worded in every language this build offers. */
  @Test
  void everyPolicyViolationIsWordedInEveryLanguage() {
    forEachLanguage(
        language -> {
          for (PolicyViolation violation : PolicyViolation.values()) {
            assertSaysSomething(PolicyViolationText.sentenceFor(language, violation), violation);
          }
        });
  }

  @Test
  void everyReasonASessionEndedIsWordedInEveryLanguage() {
    forEachLanguage(
        language -> {
          for (SessionEndedReason reason : SessionEndedReason.values()) {
            assertSaysSomething(language.say(SessionEndedText.keyFor(reason)), reason);
          }
          assertSaysSomething(language.say(SessionEndedText.LOGGED_OUT), "a logout");
          assertSaysSomething(language.say(SessionEndedText.SERVICE_LOST), "a service that went");
        });
  }

  @Test
  void everyRefusalOfTheAdministrationPanelIsWordedInEveryLanguage() {
    forEachLanguage(
        language -> {
          for (AdministrationRefusedReason reason : AdministrationRefusedReason.values()) {
            assertSaysSomething(
                language.say(AdministrationRefusedText.keyFor(reason)), reason);
          }
        });
  }

  /**
   * Issue #16: every reason the AuthenticationService could not be reached, worded in every language
   * this build offers. A reason added in the transport and worded nowhere would put a blank on the
   * one screen that exists to say something.
   */
  @Test
  void everyReasonTheServiceCannotBeReachedIsWordedInEveryLanguage() {
    forEachLanguage(
        language -> {
          for (ServiceUnreachableReason reason : ServiceUnreachableReason.values()) {
            assertSaysSomething(language.say(ServiceUnreachableText.keyFor(reason)), reason);
          }
          assertSaysSomething(
              language.say(ServiceUnreachableText.CANNOT_START),
              "an application that will not start");
        });
  }

  @Test
  void everyRoleAndEveryBandIsWordedInEveryLanguage() {
    forEachLanguage(
        language -> {
          for (Role role : Role.values()) {
            assertSaysSomething(AccountText.nameOf(language, role), role);
          }
          for (PasswordStrength band : PasswordStrength.values()) {
            assertSaysSomething(AccountText.bandOf(language, Optional.of(band)), band);
          }
          assertSaysSomething(
              AccountText.bandOf(language, Optional.empty()), "an Account awaiting enrolment");
          assertSaysSomething(
              AccountText.preferenceOf(language, Optional.empty()), "no language preference");
          assertSaysSomething(
              AccountText.lockoutOf(language, Optional.of(Duration.ofMinutes(10))), "a Lockout");
          assertSaysSomething(AccountText.lockoutOf(language, Optional.empty()), "no Lockout");
        });
  }

  /**
   * The wait is a number in a sentence, and the sentence changes with the number. Both forms are
   * asked for here, because a language that worded only one of them would read as broken exactly
   * once — on the shortest wait there is.
   */
  @Test
  void aWaitIsSaidWithItsNumberInEveryLanguage() {
    forEachLanguage(
        language -> {
          String oneMinute = LockoutText.waitOf(language, Duration.ofSeconds(30));
          String someMinutes = LockoutText.waitOf(language, Duration.ofMinutes(10));

          assertTrue(oneMinute.contains("1"), () -> "no number in " + oneMinute);
          assertTrue(someMinutes.contains("10"), () -> "no number in " + someMinutes);
          assertNotEquals(oneMinute, someMinutes);
          assertTrue(
              LockoutText.forA(language, Duration.ofMinutes(10)).contains(someMinutes),
              "the refusal should carry the wait");
        });
  }

  /** What "fails visibly" means: a key nothing ships wording for is not quietly empty. */
  @Test
  void aKeyThisBuildShipsNoWordingForThrows() {
    InterfaceLanguage language = InterfaceLanguage.of(SPANISH);

    assertThrows(MissingResourceException.class, () -> language.say("nothing.is.said.here"));
  }

  /**
   * A machine set to a language this build has wording for is drawn in it, and one set to anything
   * else is drawn in the first language offered rather than in a mixture.
   */
  @Test
  void theClosestOfferedLanguageIsTheOneDrawn() {
    assertEquals(SPANISH, InterfaceLanguage.of(SPANISH).locale());
    assertEquals(ENGLISH, InterfaceLanguage.of(ENGLISH).locale());
    assertEquals(
        SPANISH,
        InterfaceLanguage.of(Locale.forLanguageTag("es-MX")).locale(),
        "a regional variant reads the language it is a variant of");
    assertEquals(
        InterfaceLanguage.offered().get(0),
        InterfaceLanguage.of(Locale.forLanguageTag("fi")).locale(),
        "a language with no bundle is drawn in the first one offered");
  }

  /**
   * A machine whose locale names nothing at all still gets a window. Not being able to work out
   * which language somebody reads is a reason to pick one, never a reason to draw nothing.
   */
  @Test
  void aLocaleThatNamesNoLanguageIsDrawnInTheFirstOneOffered() {
    Locale first = InterfaceLanguage.offered().get(0);

    assertEquals(first, InterfaceLanguage.of(Locale.ROOT).locale());
    assertEquals(first, InterfaceLanguage.of(new Locale.Builder().build()).locale());
  }

  /** Criterion 2 at its root: what a screen nobody has authenticated in front of follows. */
  @Test
  void theMachinesOwnLanguageIsTheOneItDisplaysIn() {
    Locale machine = Locale.getDefault(Locale.Category.DISPLAY);
    try {
      Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("es-ES"));

      assertEquals(SPANISH, InterfaceLanguage.ofTheMachine().locale());
    } finally {
      Locale.setDefault(Locale.Category.DISPLAY, machine);
    }
  }

  /** A selector names a language in the language it is: somebody has to recognise their own. */
  @Test
  void aLanguageIsNamedInItself() {
    assertEquals("English", InterfaceLanguage.nameOf(ENGLISH));
    assertEquals("Español", InterfaceLanguage.nameOf(SPANISH));
  }

  private static void assertNotEquals(String one, String other) {
    assertFalse(one.equals(other), () -> "both forms read " + one);
  }

  private static void forEachLanguage(java.util.function.Consumer<InterfaceLanguage> assertion) {
    for (Locale offered : InterfaceLanguage.offered()) {
      assertion.accept(InterfaceLanguage.of(offered));
    }
  }

  private static void assertSaysSomething(String sentence, Object about) {
    assertNotNull(sentence, () -> "nothing is said about " + about);
    assertFalse(sentence.isBlank(), () -> "nothing is said about " + about);
  }

  /** How many things a message is handed, which is what a translation must not change. */
  private static int argumentsOf(String pattern, Locale locale) {
    return new MessageFormat(pattern, locale).getFormatsByArgumentIndex().length;
  }

  /**
   * Where a language's wording lives. The first language offered is the base bundle itself, which
   * is what a machine this build ships no wording for is drawn from; every other one has a file of
   * its own, and a missing one fails at {@link #everyOfferedLanguageHasWordingOfItsOwn} rather than
   * quietly reading the base bundle twice.
   */
  private static String fileFor(Locale offered) {
    return offered.equals(InterfaceLanguage.offered().get(0)) ? BASE : bundleFor(offered);
  }

  private static String bundleFor(Locale offered) {
    return "messages_" + offered.toLanguageTag() + ".properties";
  }

  private static Set<String> keysOf(String file) {
    return read(file).stringPropertyNames();
  }

  /**
   * Read as UTF-8, which is what a bundle is: the ResourceBundle the windows are drawn from reads
   * one that way, and a test that read the same file as Latin-1 would be comparing something else.
   */
  private static Properties read(String file) {
    Properties wording = new Properties();
    InputStream stream = WordingTest.class.getResourceAsStream(file);
    assertNotNull(stream, () -> file + " is missing from the jar the windows come in");
    try (Reader sentences = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      wording.load(sentences);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + file, e);
    }
    return wording;
  }
}
