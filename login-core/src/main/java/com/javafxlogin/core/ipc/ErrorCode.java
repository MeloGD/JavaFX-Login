package com.javafxlogin.core.ipc;

/**
 * Why a request other than an authentication attempt was refused.
 *
 * <p>Unlike {@link DeniedReason} these may be specific, because they answer a request the caller
 * had to be authorised to make in the first place — or, in Bootstrap's case, one whose answer a
 * fresh install reveals anyway.
 */
public enum ErrorCode {

  /** Bootstrap was attempted when the single Administrator already exists. */
  ADMINISTRATOR_EXISTS,

  /**
   * Bootstrap was attempted by a peer the operating system does not treat as an administrator of
   * this machine, or by one it would not name at all. The person is told which of their two
   * identities was refused, because otherwise they would go looking for a wrong password.
   */
  NOT_MACHINE_ADMINISTRATOR,

  /**
   * A request only an Administrator may make arrived from a Session granted in another Role. The
   * caller authenticated, so they are told plainly which of the two they are.
   */
  NOT_ADMINISTRATOR,

  /**
   * A request named an Account that does not exist. Answered plainly, and only to an Administrator
   * whose Session the service granted: an Administrator who has just cleared a Lockout for a
   * mistyped name would otherwise be told it worked, and the colleague they were trying to release
   * would stay locked out.
   */
  NO_SUCH_ACCOUNT,

  /**
   * The CredentialStore could not be read or written. Says nothing about why: the caller can only
   * retry or give up either way, and the detail belongs in the service's own record, not in a
   * response an unprivileged client receives.
   */
  STORE_UNAVAILABLE,

  /**
   * An export named a destination the service will not write to: one that is not an absolute path,
   * one whose directory does not exist, one inside the directory the service keeps its own files
   * in, or one where something is already there. Answered as one code rather than four, because
   * every one of them is answered by choosing another path — and because a privileged process that
   * reported which paths exist would be answering questions it was not asked.
   */
  EXPORT_DESTINATION_REFUSED,

  /**
   * The record could not be read, or the copy could not be written. Nothing is left at the
   * destination: half an export is a record that stops for a reason it does not state.
   */
  EXPORT_FAILED
}
