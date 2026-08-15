package com.javafxlogin.core.audit;

/**
 * Where AuthenticationEvents are written.
 *
 * <p>Write-only, deliberately: there is no method here that reads an event back, because an in-app
 * viewer would turn the record of what happened into one more thing to read out of the application
 * it is auditing. Reviewing the log means exporting it and reading it with your own tools.
 *
 * <p>An implementation must not fail an operation by failing to record it. A full disk locking
 * every Operator out of the ProtectedFeature would be this system causing the outage it exists to
 * prevent — so recording swallows what it cannot write.
 *
 * <p>The HMAC chain, the rotation and the export belong to the audit log's own ticket. What this
 * interface fixes now is that whoever records an event does not know, and cannot depend on, which
 * of those is behind it.
 */
@FunctionalInterface
public interface AuthenticationEventLog {

  /** Records one event, or silently fails to. Never throws. */
  void record(AuthenticationEvent event);
}
