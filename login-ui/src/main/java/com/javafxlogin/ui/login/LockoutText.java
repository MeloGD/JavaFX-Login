package com.javafxlogin.ui.login;

import java.time.Duration;

/**
 * What a person is told about a Lockout, wherever they meet one.
 *
 * <p>Two screens can be refused by the same Lockout — the login screen, and the enrolment screen,
 * because a wrong secret counts against an Account like a wrong password. They say the same sentence
 * because it is the same refusal about the same Account, and a second copy of the wording is a
 * second copy to translate, to round differently, and eventually to disagree with.
 */
final class LockoutText {

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String LOCKED_OUT =
      "La cuenta está bloqueada temporalmente tras varios intentos fallidos."
          + " Vuelve a intentarlo dentro de %s.";

  private LockoutText() {}

  /**
   * The refusal, with the wait in it. Said with a number because a person who is not told how long
   * simply keeps trying; it names no Account and offers no way out, because the wait is the point
   * and an Administrator is who shortens it.
   */
  static String forA(Duration remaining) {
    return LOCKED_OUT.formatted(waitOf(remaining));
  }

  /**
   * The wait, in whole minutes and never in none: rounded up so that a screen saying "one minute" is
   * never a screen someone is refused after, and floored at one so that the shortest wait is still a
   * wait rather than a zero to argue with.
   */
  private static String waitOf(Duration remaining) {
    long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
    return minutes == 1 ? "1 minuto" : minutes + " minutos";
  }
}
