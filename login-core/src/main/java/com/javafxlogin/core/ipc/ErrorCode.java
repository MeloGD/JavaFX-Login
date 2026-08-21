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
   * An Account already holds the name a new one was to be created under. Answered plainly, for the
   * reason {@link #NO_SUCH_ACCOUNT} is: the caller is an Administrator whose Session the service
   * granted, and one who is not told would go on handing out an enrolment secret for somebody
   * else's Account.
   */
  ACCOUNT_EXISTS,

  /**
   * An enrolment was asked for on behalf of the Administrator — creating a second one, or resetting
   * the one that exists. There is exactly one Administrator, it comes into existence at the
   * first-run wizard, and its password is chosen there by the person who will use it. Nothing about
   * this flow applies to it: there is no second Administrator to hand a secret to, and an
   * Administrator who could reset their own password by asking would be an Administrator whose
   * password an attacker with their Session need never have known.
   */
  CANNOT_ENROL_THE_ADMINISTRATOR,

  /**
   * A SecretVault request arrived from a Session that is not an Operator's — in practice the
   * Administrator's, which is the case ADR-0005 is about. Refused here, in the privileged process, so
   * that a patched client cannot convert an Administrator Session into Vault access directly: the way
   * in is to create an Operator and enrol it, and those two events are written to a record that
   * cannot be edited. Said plainly, like {@link #NOT_ADMINISTRATOR}, because the caller
   * authenticated and is owed which of the two Roles they hold.
   */
  NOT_AN_OPERATOR,

  /**
   * An Operator asked the SecretVault for something and holds no wrapped copy of the DataKey, so
   * there was no key their password could have derived. It is what an Account provisioned before this
   * Vault existed looks like, and what one whose password an Administrator has just taken away looks
   * like until they enrol again. The remedy is a reset and an enrolment, which is why it is said
   * rather than folded into {@link #VAULT_UNAVAILABLE}.
   */
  NO_VAULT_ACCESS,

  /**
   * Nothing is kept in the SecretVault under the name that was asked for. Said plainly to a Session
   * the service granted in the Role that reaches the Vault: a ProtectedFeature that is owed a
   * credential and told nothing would retry forever. It says nothing about what else the Vault holds,
   * because no request lists that.
   */
  NO_SUCH_SECRET,

  /**
   * The SecretVault could not be read or written, or its key file is not the one it was written
   * under. Says nothing about which: the caller can only retry or give up either way, exactly as with
   * {@link #STORE_UNAVAILABLE}.
   */
  VAULT_UNAVAILABLE,

  /**
   * A delete named the single Administrator. There is exactly one, it is what administers the
   * deployment, and one that could be deleted from a Session would leave nobody able to manage
   * Accounts — ADR-0005 puts it as administrative access never being silently narrowed or widened.
   */
  CANNOT_DELETE_THE_ADMINISTRATOR,

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
  EXPORT_FAILED,

  /**
   * A Backup named a destination the service will not write to, by the same rule {@link
   * #EXPORT_DESTINATION_REFUSED} states and for the same reasons. It is a code of its own rather
   * than that one because the two files are different files: an Administrator who has just been told
   * a path was refused should not have to work out which of the two things they asked for it was
   * about.
   */
  BACKUP_DESTINATION_REFUSED,

  /**
   * An import named a source the service will not read: one that is not an absolute path, or one
   * inside the directory the service keeps its own files in. The second is the one that matters —
   * the privileged process reading a path a client chose must not be a way to have it open its own
   * store, its own key files or the record it writes.
   */
  BACKUP_SOURCE_REFUSED,

  /**
   * The Backup did not open. The password is wrong, the file was edited, or it was never a Backup
   * this build wrote. One code for all three because there is one remedy — the right file and the
   * right password — and because a privileged process that told them apart would be telling whoever
   * is guessing which of their guesses was closest. The store is untouched.
   */
  BACKUP_NOT_READ,

  /**
   * The Backup opened and holds a CredentialStore this build does not read: one written before or
   * after this schema. Refused rather than migrated, because a restore that guessed at rows shaped
   * for other columns would be the corruption a backup exists to avoid. The remedy is the build that
   * wrote it, and the store is untouched.
   */
  BACKUP_NOT_THIS_SCHEMA,

  /**
   * The Backup opened and names no Administrator, so restoring it would leave a deployment nobody
   * can manage and no wizard would offer to fix — the FirstRunWizard is offered only while no
   * Administrator exists, and this one would have replaced the Administrator with Accounts that
   * cannot create another. Refused before anything is written.
   */
  BACKUP_HAS_NO_ADMINISTRATOR,

  /**
   * The Backup could not be written, could not be read from the disk, or would not go into the
   * store. Nothing is left at the destination on an export, and nothing is changed in the store on
   * an import: the write is one transaction, so a failure is the store exactly as it was.
   */
  BACKUP_FAILED
}
