package com.javafxlogin.core.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * The one-time secret an Administrator hands over so that an Operator can set their own password.
 *
 * <p>It is the whole of what an Administrator ever knows about an Operator's credentials, and it is
 * knowledge with a short life: it is shown once, it is consumed by the first use, and what the
 * CredentialStore keeps is a hash of it. ADR-0012 records why that is worth having even though an
 * Administrator can mint an Operator of their own.
 *
 * <p>It carries 128 bits, and it is written in Crockford's base 32 because a person reads it off one
 * screen and types it into another. That alphabet leaves out the four characters that are read as
 * each other by hand — I, L, O and U — and this class reads the first three back as the ones they
 * were mistaken for, so somebody who writes a zero as an O is not refused for it. Twenty-six
 * characters of thirty-two would be 130 bits, so the last character carries three bits of the secret
 * and two of nothing; a text that puts anything in those two is refused, because a secret with two
 * spellings is a secret this class cannot compare by its text.
 *
 * <p><b>What stands behind it is SHA-256 and not Argon2id.</b> Argon2id is slow on purpose because a
 * password is something a person chose and is therefore guessable; this is 128 bits chosen by a
 * SecureRandom, which no amount of guessing reaches, and there is nothing for a work factor to buy.
 * ASVS 5.0 allows a fast cryptographic hash for exactly this case — a high-entropy secret the
 * service generated — and paying Argon2id's hundred milliseconds here would buy nothing but a slower
 * enrolment screen.
 */
public final class EnrolmentSecret {

  /** Crockford's base 32: no I, no L, no O, no U. */
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final int BITS_PER_CHARACTER = 5;
  private static final int LENGTH_IN_BYTES = 16;
  private static final int LENGTH_IN_CHARACTERS = 26;

  /** Short enough that a person holds one group in their head while they cross the room. */
  private static final int CHARACTERS_PER_GROUP = 4;

  private static final String DIGEST = "SHA-256";

  private final byte[] value;

  private EnrolmentSecret(byte[] value) {
    this.value = value;
  }

  /** A secret nobody has held before, out of the randomness the service was given. */
  public static EnrolmentSecret generate(SecureRandom random) {
    Objects.requireNonNull(random, "random");
    byte[] value = new byte[LENGTH_IN_BYTES];
    random.nextBytes(value);
    return new EnrolmentSecret(value);
  }

  /**
   * Reads back what somebody typed, or answers that it is not one of these at all.
   *
   * <p>Dashes and spaces are ignored, lower case is read as upper, and the three characters
   * Crockford leaves out because they are confused for others are read as the ones they were
   * confused for. Everything else is refused rather than guessed at: what comes back from here is
   * compared against a stored hash, so a lenient reading would be this class deciding that two
   * different secrets are one.
   */
  public static Optional<EnrolmentSecret> parse(char[] typed) {
    Objects.requireNonNull(typed, "typed");
    StringBuilder canonical = new StringBuilder(LENGTH_IN_CHARACTERS);
    for (char typedCharacter : typed) {
      if (typedCharacter == '-' || typedCharacter == ' ') {
        continue;
      }
      int index = ALPHABET.indexOf(unconfuse(Character.toUpperCase(typedCharacter)));
      if (index < 0 || canonical.length() == LENGTH_IN_CHARACTERS) {
        return Optional.empty();
      }
      canonical.append(ALPHABET.charAt(index));
    }
    if (canonical.length() != LENGTH_IN_CHARACTERS) {
      return Optional.empty();
    }
    EnrolmentSecret secret = new EnrolmentSecret(decode(canonical));
    // The two bits the last character does not carry have to be the two bits it was written with,
    // or this text is a second spelling of the secret that comes back.
    return encode(secret.value).contentEquals(canonical) ? Optional.of(secret) : Optional.empty();
  }

  /**
   * The secret as it is shown to the Administrator, once, and as they write it down.
   *
   * <p>A String and not a {@code char[]}, unlike a password: this one is drawn on a screen and read
   * out loud, so every layer between here and the label already holds a copy. What is worth
   * protecting about it is protected by it being consumable once and hashed at rest, not by the
   * shape of the array it travelled in.
   */
  public String text() {
    String encoded = encode(value);
    StringBuilder text = new StringBuilder();
    for (int at = 0; at < encoded.length(); at += CHARACTERS_PER_GROUP) {
      if (at > 0) {
        text.append('-');
      }
      text.append(encoded, at, Math.min(at + CHARACTERS_PER_GROUP, encoded.length()));
    }
    return text.toString();
  }

  /** What the CredentialStore keeps in place of the secret: the digest, as hexadecimal. */
  public String hashed() {
    return HexFormat.of().formatHex(digestOf(value));
  }

  /**
   * Whether this is the secret that was issued, compared against the hash the store kept.
   *
   * <p>Compared in constant time. The comparison is between two digests rather than between two
   * secrets, so a stopwatch on it learns nothing that matters — but there is no reason to hand it
   * even that, and none to remember which of the two comparisons in this system was the one that
   * mattered.
   */
  public boolean matches(String hashed) {
    Objects.requireNonNull(hashed, "hashed");
    return MessageDigest.isEqual(
        hashed().getBytes(StandardCharsets.US_ASCII), hashed.getBytes(StandardCharsets.US_ASCII));
  }

  /** Redacted whole: a secret that printed itself would be a secret in every stack trace. */
  @Override
  public String toString() {
    return "EnrolmentSecret[redacted]";
  }

  /** The three characters Crockford left out, read as the ones somebody meant by them. */
  private static char unconfuse(char character) {
    return switch (character) {
      case 'O' -> '0';
      case 'I', 'L' -> '1';
      default -> character;
    };
  }

  private static String encode(byte[] value) {
    StringBuilder encoded = new StringBuilder(LENGTH_IN_CHARACTERS);
    for (int character = 0; character < LENGTH_IN_CHARACTERS; character++) {
      int index = 0;
      for (int bit = 0; bit < BITS_PER_CHARACTER; bit++) {
        index = (index << 1) | bitAt(value, character * BITS_PER_CHARACTER + bit);
      }
      encoded.append(ALPHABET.charAt(index));
    }
    return encoded.toString();
  }

  private static byte[] decode(CharSequence canonical) {
    byte[] value = new byte[LENGTH_IN_BYTES];
    for (int character = 0; character < LENGTH_IN_CHARACTERS; character++) {
      int index = ALPHABET.indexOf(canonical.charAt(character));
      for (int bit = 0; bit < BITS_PER_CHARACTER; bit++) {
        int at = character * BITS_PER_CHARACTER + bit;
        if (at < LENGTH_IN_BYTES * Byte.SIZE
            && (index & (1 << (BITS_PER_CHARACTER - 1 - bit))) != 0) {
          value[at / Byte.SIZE] |= (byte) (1 << (Byte.SIZE - 1 - at % Byte.SIZE));
        }
      }
    }
    return value;
  }

  /** The bit at that position, counting from the first bit of the first byte, or the padding. */
  private static int bitAt(byte[] value, int at) {
    if (at >= value.length * Byte.SIZE) {
      return 0;
    }
    return (value[at / Byte.SIZE] >> (Byte.SIZE - 1 - at % Byte.SIZE)) & 1;
  }

  private static byte[] digestOf(byte[] value) {
    try {
      return MessageDigest.getInstance(DIGEST).digest(value);
    } catch (NoSuchAlgorithmException e) {
      // Every Java runtime this product can start on has SHA-256. A runtime without one is not a
      // machine this service can protect anything on.
      throw new IllegalStateException("This Java runtime has no " + DIGEST, e);
    }
  }
}
