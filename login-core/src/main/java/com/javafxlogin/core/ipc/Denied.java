package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * Authentication was refused.
 *
 * <p>Two attempts that failed for different reasons produce equal Denied values, which is the
 * point: the login screen must not become an oracle for which Accounts exist.
 */
public record Denied(DeniedReason reason) implements Response {

  public Denied {
    Objects.requireNonNull(reason, "reason");
  }
}
