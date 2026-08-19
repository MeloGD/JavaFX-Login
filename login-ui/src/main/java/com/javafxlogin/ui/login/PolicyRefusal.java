package com.javafxlogin.ui.login;

import com.javafxlogin.core.policy.PolicyViolation;
import java.util.List;
import java.util.Objects;

/**
 * A name or a password broke the AccountPolicy, and nothing was created or changed.
 *
 * <p>It carries every rule that was broken rather than the first one, so the person can fix the
 * whole thing at once instead of discovering it a rule at a time. The rules are named rather than
 * worded: turning them into sentences is this module's job, which is what lets the wording change,
 * and be translated, without the privileged process knowing.
 *
 * <p>It is the answer to the screens that choose a credential — the first-run wizard and the
 * enrolment screen — because it is the same refusal by the same rules in the same process. Neither
 * screen has the Account it names; the wizard's does not exist yet and the enrolment screen's is
 * already there and unchanged. It is also the answer to an Administrator creating an Account, where
 * what broke the rules is the name alone: the panel never chooses a password, so no refusal it
 * receives can be about one.
 */
public record PolicyRefusal(List<PolicyViolation> violations)
    implements FirstRunOutcome, EnrolmentOutcome, AccountProvisioned {

  public PolicyRefusal {
    Objects.requireNonNull(violations, "violations");
    if (violations.isEmpty()) {
      throw new IllegalArgumentException("A refusal carries at least one reason");
    }
    violations = List.copyOf(violations);
  }
}
