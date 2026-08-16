package com.javafxlogin.core.audit;

/**
 * What an {@link AuthenticationEvent} records.
 *
 * <p>The set grew one ticket at a time, alongside the thing being recorded, so that no build ever
 * had a constant nothing writes. The audit log's own ticket is the one that completes it: story 73
 * names authentication attempts, Lockouts, Account changes, configuration changes and exports, and
 * all five are here. What is still missing is what is still unbuilt — enrolment adds the Account
 * changes only it can make, and the SecretVault adds its own.
 *
 * <p>A failed authentication says here why it failed, which is the one place it may be said. The
 * client is told {@code AUTH_FAILED} and nothing more, because telling it apart at the login screen
 * would name which Accounts exist; whoever exports this log has already proved they administer the
 * deployment, and is owed the difference between a wrong password and a name nobody holds.
 */
public enum AuthenticationEventType {

  /**
   * The single Administrator was created by the FirstRunWizard. The only Account change this build
   * can make: enrolment, which is how Operators come into existence, is its own ticket.
   */
  ADMINISTRATOR_CREATED,

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
