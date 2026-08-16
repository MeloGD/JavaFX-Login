package com.javafxlogin.core.audit;

/**
 * What an {@link AuthenticationEvent} records.
 *
 * <p>The set grew one ticket at a time, alongside the thing being recorded, so that no build ever
 * had a constant nothing writes. The audit log's own ticket is the one that completed it: story 73
 * names authentication attempts, Lockouts, Account changes, configuration changes and exports, and
 * all five are here. Enrolment has since added the Account changes only it can make. What is still
 * missing is what is still unbuilt — the SecretVault adds its own.
 *
 * <p>A failed authentication says here why it failed, which is the one place it may be said. The
 * client is told {@code AUTH_FAILED} and nothing more, because telling it apart at the login screen
 * would name which Accounts exist; whoever exports this log has already proved they administer the
 * deployment, and is owed the difference between a wrong password and a name nobody holds.
 */
public enum AuthenticationEventType {

  /** The single Administrator was created by the FirstRunWizard. */
  ADMINISTRATOR_CREATED,

  /**
   * An Administrator created an Account, which comes into existence with no password and an
   * enrolment secret somebody else will turn into one.
   */
  ACCOUNT_CREATED,

  /**
   * A one-time enrolment secret was issued for an Account — with the Account, on a reset, or on its
   * own where one was lost or ran out. What it was is not here and is nowhere else either: the
   * record says that an enrolment was issued, because a record that said what it was would be a
   * copy of the secret outliving the moment it was shown.
   */
  ENROLMENT_SECRET_ISSUED,

  /** An Administrator took an Account's password away, which is what starts a reset. */
  PASSWORD_RESET_INITIATED,

  /** Somebody offered a valid enrolment secret and chose the password that Account now has. */
  ENROLMENT_COMPLETED,

  /**
   * An enrolment secret was offered and refused: wrong, expired, already used, or against an
   * Account that has a password and is waiting for nothing. Recorded against the Account where a
   * name holds one, and against the placeholder where it does not — story 77 again, because the
   * enrolment screen has a name box like every other screen.
   */
  ENROLMENT_FAILED,

  /** An Account offered the right password in the Role it holds and was admitted. */
  AUTHENTICATION_SUCCEEDED,

  /** An Account exists and the password offered for it was wrong. */
  AUTHENTICATION_FAILED_WRONG_PASSWORD,

  /**
   * No Account holds the name that was typed. Recorded against the placeholder rather than against
   * that name: story 77, because the string typed into the name box is eventually somebody's
   * password typed into the wrong one.
   */
  AUTHENTICATION_FAILED_NO_SUCH_ACCOUNT,

  /**
   * The right password for an Account that does not hold the Role it asked to act in — the
   * Administrator answering the login screen, which admits Operators only.
   */
  AUTHENTICATION_FAILED_WRONG_ROLE,

  /** An attempt against an Account that is refused for a while, which was refused again. */
  AUTHENTICATION_REFUSED_LOCKED_OUT,

  /**
   * An attempt made while a Session was already live, refused before any Account was looked at and
   * so recorded against nobody. A run of these is what someone trying to take a machine away from
   * the person sitting at it looks like.
   */
  AUTHENTICATION_REFUSED_SESSION_ALREADY_LIVE,

  /**
   * Somebody tried to log in to an Account that has no password yet, and was sent to the enrolment
   * screen instead. Not counted as a failure against the Account: there was no password to be wrong
   * about, and an Account nobody has enrolled yet could otherwise be locked out of its own
   * enrolment by whoever guessed its name first.
   */
  AUTHENTICATION_REFUSED_ENROLMENT_REQUIRED,

  /**
   * A Session was ended because the machine's clock stopped agreeing with the clock that cannot be
   * moved. Recorded so that moving a clock is not merely useless but visible — whoever reads the
   * exported log sees when the service could no longer account for a Session's idle time.
   */
  SESSION_ENDED_BY_A_CLOCK_JUMP,

  /** An Administrator changed the configuration of the application. */
  CONFIGURATION_CHANGED,

  /**
   * An Account failed authentication often enough to be refused for a while. Recorded against the
   * Account, because whoever reads the exported log wants to know which one stopped answering — and
   * a run of these is what a guessing attack looks like from the outside.
   */
  ACCOUNT_LOCKED_OUT,

  /**
   * A Lockout was cleared before its time was up. Recorded against the Account released rather than
   * against whoever released it: there is one Administrator, so the second name would be the same
   * name every time, and the first is the one a reader is looking for.
   */
  LOCKOUT_CLEARED,

  /**
   * An Administrator copied the record out to read it with their own tools. Recorded after the copy
   * is made, so the copy does not claim to contain the export that produced it; the next export
   * shows this one.
   */
  AUTHENTICATION_EVENTS_EXPORTED
}
