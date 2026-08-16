package com.javafxlogin.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one-time secret an Administrator hands over, which is the only thing they ever know about an
 * Operator's credentials.
 *
 * <p>Two things are asserted here and nowhere else: that it carries the 128 bits the ticket asks
 * for, and that a person can read it off one screen and type it into another without the alphabet
 * fighting them.
 */
class EnrolmentSecretTest {

  /** Crockford's, which is the alphabet the four confusable characters are missing from. */
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * 128 bits, which is what lets a fast hash stand behind it. Twenty-six characters of a
   * thirty-two-character alphabet would be 130, so the last one carries three bits of the secret
   * and two of nothing: 32<sup>25</sup> × 8 = 2<sup>128</sup>, counted here by trying every
   * character in the last place and finding that eight of them are a secret.
   */
  @Test
  void carriesAHundredAndTwentyEightBits() {
    String allButTheLast = "0000-0000-0000-0000-0000-0000-0";

    long acceptedInTheLastPlace =
        ALPHABET.chars().filter(c -> parse(allButTheLast + (char) c).isPresent()).count();

    assertEquals(26, withoutDashes(EnrolmentSecret.generate(RANDOM)).length());
    assertEquals(8, acceptedInTheLastPlace, "the last character is not carrying three bits");
  }

  @Test
  void twoSecretsAreNeverTheSame() {
    Set<String> seen = new HashSet<>();

    for (int i = 0; i < 500; i++) {
      assertTrue(seen.add(EnrolmentSecret.generate(RANDOM).text()), "a secret came round twice");
    }
  }

  /** What is shown is what can be typed back, which is the whole of what "transcribe" means. */
  @Test
  void whatIsShownParsesBackToWhatItWas() {
    EnrolmentSecret secret = EnrolmentSecret.generate(RANDOM);

    assertEquals(Optional.of(secret.text()), parse(secret.text()).map(EnrolmentSecret::text));
  }

  @Test
  void isBrokenIntoGroupsShortEnoughToHoldInTheHead() {
    String text = EnrolmentSecret.generate(RANDOM).text();

    for (String group : text.split("-")) {
      assertTrue(group.length() <= 4, () -> "a group nobody can hold: " + text);
    }
  }

  /**
   * Crockford's alphabet, and the reason for choosing it: the four characters a person confuses
   * when copying by hand are not in it, so a secret cannot contain one.
   */
  @Test
  void neverContainsACharacterThatIsReadAsAnother() {
    for (int i = 0; i < 200; i++) {
      String text = EnrolmentSecret.generate(RANDOM).text();

      assertTrue(
          text.chars().noneMatch(c -> "ILOU".indexOf(c) >= 0), () -> "unreadable by hand: " + text);
    }
  }

  /** And the other half of that choice: the confusions are read as what was meant. */
  @Test
  void readsTheCharactersAPersonSubstitutesAsTheOnesTheyMeant() {
    String zeroes = "0000-0000-0000-0000-0000-0000-00";
    String ones = "1111-1111-1111-1111-1111-1111-10";

    assertEquals(textOf(zeroes), textOf("OOOO-oooo-0000-0000-0000-0000-00"));
    assertEquals(textOf(ones), textOf("IiLl-1111-1111-1111-1111-1111-10"));
  }

  @Test
  void readsWhatWasTypedInLowerCaseAndWithoutTheDashes() {
    EnrolmentSecret secret = EnrolmentSecret.generate(RANDOM);
    String typed = secret.text().replace("-", " ").toLowerCase(Locale.ROOT);

    assertEquals(Optional.of(secret.text()), parse(typed).map(EnrolmentSecret::text));
  }

  @Test
  void refusesWhatIsNotOneOfItsSecrets() {
    assertEquals(Optional.empty(), parse(""));
    assertEquals(Optional.empty(), parse("not-a-secret"));
    assertEquals(Optional.empty(), parse("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-U0"), "U is not in it");
    assertEquals(Optional.empty(), parse("0000-0000-0000-0000-0000-0000-0"), "too short");
    assertEquals(Optional.empty(), parse("0000-0000-0000-0000-0000-0000-000"), "too long");
  }

  /**
   * The last character carries three bits of the secret and two of nothing. A text that puts
   * anything in those two is a second spelling of a secret, and a secret has one spelling.
   */
  @Test
  void refusesASecondSpellingOfTheSameSecret() {
    assertTrue(parse("0000-0000-0000-0000-0000-0000-00").isPresent());

    assertEquals(Optional.empty(), parse("0000-0000-0000-0000-0000-0000-01"));
  }

  /**
   * The hash is a SHA-256 of the 128 bits, pinned against two vectors computed outside this build.
   * ADR-0012 argues at length that a fast hash is the right one here and Argon2id is not; this is
   * what stops that argument from being the only thing holding the algorithm in place — a build
   * that quietly changed it, salted it, or hashed the text instead of the bits fails here.
   */
  @Test
  void hashesTheBitsWithSha256AndNothingElse() {
    assertEquals(
        "374708fff7719dd5979ec875d56cd2286f6d3cf7ec317a3b25632aab28ec37bb",
        parse("0000-0000-0000-0000-0000-0000-00").orElseThrow().hashed(),
        "not the SHA-256 of sixteen zero bytes");
    assertEquals(
        "5ac6a5945f16500911219129984ba8b387a06f24fe383ce4e81a73294065461b",
        parse("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW").orElseThrow().hashed(),
        "not the SHA-256 of sixteen bytes of ones");
  }

  /** What the CredentialStore keeps is never the secret, and never long enough to be one. */
  @Test
  void hashesToSomethingThatIsNotTheSecret() {
    EnrolmentSecret secret = EnrolmentSecret.generate(RANDOM);

    String hashed = secret.hashed();

    assertFalse(hashed.contains(withoutDashes(secret)), "the secret is in its own hash");
    assertEquals(64, hashed.length(), "not a SHA-256 written as hexadecimal");
  }

  @Test
  void matchesTheHashOfItselfAndNoOther() {
    EnrolmentSecret secret = EnrolmentSecret.generate(RANDOM);
    EnrolmentSecret another = EnrolmentSecret.generate(RANDOM);

    assertTrue(secret.matches(secret.hashed()));
    assertFalse(secret.matches(another.hashed()));
    assertFalse(secret.matches(""));
    assertNotEquals(secret.hashed(), another.hashed());
  }

  /** A secret that printed itself would end up in a stack trace, which is a place it may not be. */
  @Test
  void neverPrintsItself() {
    EnrolmentSecret secret = EnrolmentSecret.generate(RANDOM);

    assertFalse(
        secret.toString().contains(secret.text().substring(0, 4)),
        () -> "printed itself: " + secret);
  }

  private static String textOf(String typed) {
    return parse(typed)
        .orElseThrow(() -> new AssertionError("not read as a secret: " + typed))
        .text();
  }

  private static String withoutDashes(EnrolmentSecret secret) {
    return secret.text().replace("-", "");
  }

  private static Optional<EnrolmentSecret> parse(String typed) {
    return EnrolmentSecret.parse(typed.toCharArray());
  }
}
