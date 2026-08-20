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

  private static final String LOCKED_OUT = "lockout.refused";

  /**
   * The wait, whose sentence changes with the number in it. Which form a count takes is chosen
   * inside the bundle rather than here, so that a language counting differently from Spanish is a
   * matter of editing a message.
   */
  private static final String WAIT = "lockout.wait";

  private LockoutText() {}

  /**
   * The refusal, with the wait in it. Said with a number because a person who is not told how long
   * simply keeps trying; it names no Account and offers no way out, because the wait is the point
   * and an Administrator is who shortens it.
   */
  static String forA(InterfaceLanguage language, Duration remaining) {
    return language.say(LOCKED_OUT, waitOf(language, remaining));
  }

  /**
   * The wait, in whole minutes and never in none: rounded up so that a screen saying "one minute"
   * is never a screen someone is refused after, and floored at one so that the shortest wait is
   * still a wait rather than a zero to argue with.
   *
   * <p>Shared with the administration panel's list of Accounts, which reports the same wait about
   * the same Lockout and must not come to round it differently.
   */
  static String waitOf(InterfaceLanguage language, Duration remaining) {
    long minutes = Math.max(1, (remaining.toSeconds() + 59) / 60);
    return language.say(WAIT, minutes);
  }
}
