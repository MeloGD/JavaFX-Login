package com.javafxlogin.ui.login;

/**
 * Why the AuthenticationService refused something the administration panel asked for.
 *
 * <p>Specific, like {@link SecretWithheldReason} and unlike the reasons an authentication fails:
 * whoever is asking holds a Session this service granted in the Role that manages the deployment,
 * and each of these has a different remedy — a different name, a different Account, a different
 * file to write to, or nothing at all because the Session is over.
 */
public enum AdministrationRefusedReason {

  /**
   * The Session ended before the request arrived. Nothing the panel can do about it: the person is
   * handed back to the login screen, which is what happens whenever a Session ends.
   */
  SESSION_OVER,

  /**
   * The Session is not an Administrator's. It is the refusal that makes the panel unreachable
   * rather than merely unshown — every request behind it is refused in the privileged process, so
   * a client patched into drawing the screen draws an empty one.
   */
  NOT_ADMINISTRATOR,

  /** No Account holds the name the request named. */
  NO_SUCH_ACCOUNT,

  /** An Account already holds the name a new one was to be created under. */
  ACCOUNT_EXISTS,

  /**
   * The request named the single Administrator, whose password is chosen at the FirstRunWizard by
   * whoever will use it. There is nobody to hand an EnrolmentSecret to.
   */
  CANNOT_ENROL_THE_ADMINISTRATOR,

  /**
   * A delete named the single Administrator. Deleting it would leave nobody able to manage
   * Accounts.
   */
  CANNOT_DELETE_THE_ADMINISTRATOR,

  /**
   * The service will not write the copy of the record where it was asked to: somewhere that is not
   * an absolute path, a directory that does not exist, its own directory, or a file that is already
   * there. Answered as one reason, as the service answers it, because every one of them is answered
   * by choosing another path.
   */
  EXPORT_DESTINATION_REFUSED,

  /** The record could not be read or the copy could not be written, and nothing was left behind. */
  EXPORT_FAILED
}
