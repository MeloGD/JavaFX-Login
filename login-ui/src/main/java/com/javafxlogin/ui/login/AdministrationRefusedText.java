package com.javafxlogin.ui.login;

/**
 * What an Administrator is told when the AuthenticationService refuses something the panel asked
 * for.
 *
 * <p>The service names a reason and never words it, as it does with a PolicyViolation and with a
 * Session that ended, and this is the other side of that bargain — exhaustive on purpose, so that a
 * reason added over there and worded nowhere here cannot reach a person as a blank refusal.
 *
 * <p>Every string here moves to a ResourceBundle when the interface learns a second language.
 */
final class AdministrationRefusedText {

  private AdministrationRefusedText() {}

  static String sentenceFor(AdministrationRefusedReason reason) {
    return switch (reason) {
      case SESSION_OVER -> "La sesión ha terminado.";
      case NOT_ADMINISTRATOR -> "El servicio no acepta esta petición desde esta sesión.";
      case NO_SUCH_ACCOUNT -> "Ya no existe ninguna cuenta con ese nombre.";
      case ACCOUNT_EXISTS -> "Ya hay una cuenta con ese nombre.";
      case CANNOT_ENROL_THE_ADMINISTRATOR ->
          "La cuenta de administración elige su contraseña en el asistente de primera ejecución,"
              + " así que no se le puede entregar un código de un solo uso.";
      case CANNOT_DELETE_THE_ADMINISTRATOR ->
          "La cuenta de administración no se puede eliminar: sin ella nadie podría gestionar las"
              + " cuentas.";
      case EXPORT_DESTINATION_REFUSED ->
          "El servicio no escribe ahí. Elige una ruta absoluta, en una carpeta que ya exista, que"
              + " no sea la suya y donde no haya ya un archivo.";
      case EXPORT_FAILED ->
          "No se ha podido copiar el registro. No se ha dejado nada a medias en el destino.";
    };
  }
}
