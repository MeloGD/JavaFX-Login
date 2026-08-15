package com.javafxlogin.core.audit;

/**
 * What an {@link AuthenticationEvent} records.
 *
 * <p>The set grows one ticket at a time, alongside the thing being recorded, so that no build ever
 * has a constant nothing writes. The Session lifecycle ticket added the first two; the Lockout
 * ticket added the last two; authentication attempts, Account changes and exports arrive with the
 * tickets that create them.
 */
public enum AuthenticationEventType {

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
  LOCKOUT_CLEARED
}
