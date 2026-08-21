package com.javafxlogin.core.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The one place a {@code char[]} becomes bytes and back, without a String in between.
 *
 * <p>Every password this product derives a key from goes through here — the one that opens the
 * SecretVault and the one a Backup is written under — because what makes it worth having is that
 * nothing it leaves behind is out of reach of an {@code Arrays.fill}.
 *
 * <p>Passwords and secrets travel as arrays through this system so that they are not interned and
 * can be overwritten. Encoding one with {@code new String(chars).getBytes()} would hand the runtime a
 * copy nobody can reach to overwrite, which is the whole reason the arrays exist; going through NIO's
 * buffers keeps every intermediate something this class can fill with zeroes.
 */
public final class Utf8 {

  private Utf8() {}

  /** The characters as UTF-8, leaving nothing behind but the array that is returned. */
  public static byte[] bytesOf(char[] characters) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    // The buffer may be bigger than what it held, and what it held is a password.
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return bytes;
  }

  /** The bytes as characters, for handing a secret back to whoever asked for it. */
  public static char[] charsOf(byte[] bytes) {
    CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
    char[] characters = new char[decoded.remaining()];
    decoded.get(characters);
    if (decoded.hasArray()) {
      Arrays.fill(decoded.array(), '\0');
    }
    return characters;
  }
}
