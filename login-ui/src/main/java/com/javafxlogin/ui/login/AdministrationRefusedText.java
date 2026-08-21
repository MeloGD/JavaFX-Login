package com.javafxlogin.ui.login;

/**
 * Names what an Administrator is told when the AuthenticationService refuses something the panel
 * asked for.
 *
 * <p>The service names a reason and never words it, as it does with a PolicyViolation and with a
 * Session that ended, and this is the other side of that bargain — exhaustive on purpose, so that a
 * reason added over there and named nowhere here cannot reach a person as a blank refusal.
 *
 * <p>One of these is not said on this panel at all: a Session that is over closes the window and is
 * said at the login screen, which may be drawn in another language. That is why what comes back is
 * the key and never the sentence.
 */
final class AdministrationRefusedText {

  private AdministrationRefusedText() {}

  static String keyFor(AdministrationRefusedReason reason) {
    return switch (reason) {
      case SESSION_OVER -> "refused.session-over";
      case NOT_ADMINISTRATOR -> "refused.not-administrator";
      case NO_SUCH_ACCOUNT -> "refused.no-such-account";
      case ACCOUNT_EXISTS -> "refused.account-exists";
      case CANNOT_ENROL_THE_ADMINISTRATOR -> "refused.cannot-enrol-the-administrator";
      case CANNOT_DELETE_THE_ADMINISTRATOR -> "refused.cannot-delete-the-administrator";
      case EXPORT_DESTINATION_REFUSED -> "refused.export-destination-refused";
      case EXPORT_FAILED -> "refused.export-failed";
      case BACKUP_DESTINATION_REFUSED -> "refused.backup-destination-refused";
      case BACKUP_SOURCE_REFUSED -> "refused.backup-source-refused";
      case BACKUP_NOT_READ -> "refused.backup-not-read";
      case BACKUP_NOT_THIS_SCHEMA -> "refused.backup-not-this-schema";
      case BACKUP_HAS_NO_ADMINISTRATOR -> "refused.backup-has-no-administrator";
      case BACKUP_FAILED -> "refused.backup-failed";
    };
  }
}
