package com.javafxlogin.ui.login;

import com.javafxlogin.core.policy.PolicyViolation;
import java.util.List;
import java.util.Objects;

/**
 * The name or the password broke the AccountPolicy, and the Administrator was not created.
 *
 * <p>It carries every rule that was broken rather than the first one, so the person can fix the
 * whole thing at once instead of discovering it a rule at a time. The rules are named rather than
 * worded: turning them into sentences is this module's job, which is what lets the wording change,
 * and be translated, without the privileged process knowing.
 */
public record PolicyRefusal(List<PolicyViolation> violations) implements FirstRunOutcome {

  public PolicyRefusal {
    Objects.requireNonNull(violations, "violations");
    if (violations.isEmpty()) {
      throw new IllegalArgumentException("A refusal carries at least one reason");
    }
    violations = List.copyOf(violations);
  }
}
