package com.javafxlogin.core.policy;

import com.javafxlogin.core.account.PasswordStrength;
import java.util.List;
import java.util.Objects;

/**
 * What the policy makes of a proposed Account name and password.
 *
 * <p>The two halves answer different questions and must not be confused. The violations decide
 * whether the Account may exist at all; the strength decides nothing and is carried for the person
 * to read. An empty violation list is an acceptance, however weak the estimate.
 *
 * @param violations every rule the name and password break, in the order of {@link PolicyViolation}
 * @param strength the coarse band, always present, never a reason to refuse
 */
public record Assessment(List<PolicyViolation> violations, PasswordStrength strength) {

  public Assessment {
    Objects.requireNonNull(violations, "violations");
    Objects.requireNonNull(strength, "strength");
    violations = List.copyOf(violations);
  }
}
