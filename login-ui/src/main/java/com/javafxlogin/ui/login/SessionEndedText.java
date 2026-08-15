package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.SessionEndedReason;

/**
 * Turns the end of a Session into a sentence a person reads on the login screen they are handed
 * back to.
 *
 * <p>Exhaustive on purpose, as {@link PolicyViolationText} is: a reason added in the service that
 * nobody worded here would return someone to a login screen that says nothing about why the window
 * they were working in disappeared.
 *
 * <p>Every string here moves to a ResourceBundle when the interface learns a second language.
 */
final class SessionEndedText {

  /** Nothing ended the Session: the person did. */
  static final String LOGGED_OUT = "Has cerrado la sesión.";

  /**
   * The service went away mid-Session. Worded apart from a Session that ended, because the remedy
   * is to get the service running rather than to log in again — and worded plainly, because the
   * window the person was working in has closed either way.
   */
  static final String SERVICE_LOST =
      "Se ha perdido la conexión con el servicio de autenticación, así que la sesión ha terminado.";

  private SessionEndedText() {}

  static String sentenceFor(SessionEndedReason reason) {
    return switch (reason) {
      case INACTIVITY ->
          "Tu sesión se ha cerrado tras un rato sin actividad. Vuelve a acceder para continuar.";
      // Said as two possibilities because it is two: the service knows the machine's clock stopped
      // agreeing with the one that cannot be moved, and cannot know which of the two happened.
      case CLOCK_JUMPED ->
          "Tu sesión se ha cerrado porque la hora del equipo ha cambiado, o porque el equipo ha"
              + " estado suspendido.";
      case NO_SUCH_SESSION -> "Tu sesión ya no está activa. Vuelve a acceder para continuar.";
    };
  }
}
