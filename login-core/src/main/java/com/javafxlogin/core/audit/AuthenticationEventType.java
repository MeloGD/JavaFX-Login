package com.javafxlogin.core.audit;

/**
 * What an {@link AuthenticationEvent} records.
 *
 * <p>The set grows one ticket at a time, alongside the thing being recorded, so that no build ever
 * has a constant nothing writes. The Session lifecycle ticket added the two below; authentication
 * attempts, Lockouts, Account changes and exports arrive with the tickets that create them.
 */
public enum AuthenticationEventType {

  /**
   * A Session was ended because the machine's clock stopped agreeing with the clock that cannot be
   * moved. Recorded so that moving a clock is not merely useless but visible — whoever reads the
   * exported log sees when the service could no longer account for a Session's idle time.
   */
  SESSION_ENDED_BY_A_CLOCK_JUMP,

  /** An Administrator changed the configuration of the application. */
  CONFIGURATION_CHANGED
}
