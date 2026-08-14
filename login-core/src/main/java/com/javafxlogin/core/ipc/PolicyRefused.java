package com.javafxlogin.core.ipc;

import com.javafxlogin.core.policy.PolicyViolation;
import java.util.List;
import java.util.Objects;

/**
 * The request would have created or changed an Account, and the name or the password broke the
 * policy.
 *
 * <p>It carries every rule that was broken rather than the first one, so that the UI can explain
 * the whole refusal at once. Being specific is safe here in a way it is not for a failed
 * authentication: this answers someone choosing a value, not someone guessing one, and it says
 * nothing about any Account that already exists.
 */
public record PolicyRefused(List<PolicyViolation> violations) implements Response {

  public PolicyRefused {
    Objects.requireNonNull(violations, "violations");
    if (violations.isEmpty()) {
      throw new IllegalArgumentException("A refusal carries at least one reason");
    }
    violations = List.copyOf(violations);
  }
}
