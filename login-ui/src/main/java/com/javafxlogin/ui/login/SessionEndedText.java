package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.SessionEndedReason;

/**
 * Names what a person is told, on the login screen they are handed back to, about the Session that
 * has just ended.
 *
 * <p>Exhaustive on purpose, as {@link PolicyViolationText} is: a reason added in the service that
 * nobody named here would return someone to a login screen that says nothing about why the window
 * they were working in disappeared.
 *
 * <p>Keys and not sentences. The window that discovers a Session has ended is closing, and the one
 * that says so is the login screen behind it — which follows the machine, or whatever the selector
 * was set to, and not the LanguagePreference of the Account that has just stopped being admitted.
 * Wording it here would be this application saying goodbye in the last person's language to
 * whoever walks up next.
 */
final class SessionEndedText {

  /** Nothing ended the Session: the person did. */
  static final String LOGGED_OUT = "session.ended.logged-out";

  /**
   * The service went away mid-Session. Said apart from a Session that ended, because the remedy is
   * to get the service running rather than to log in again — and said plainly, because the window
   * the person was working in has closed either way.
   */
  static final String SERVICE_LOST = "session.ended.service-lost";

  private SessionEndedText() {}

  static String keyFor(SessionEndedReason reason) {
    return switch (reason) {
      case INACTIVITY -> "session.ended.inactivity";
      // Said as two possibilities because it is two: the service knows the machine's clock stopped
      // agreeing with the one that cannot be moved, and cannot know which of the two happened.
      case CLOCK_JUMPED -> "session.ended.clock-jumped";
      case NO_SUCH_SESSION -> "session.ended.no-such-session";
    };
  }
}
