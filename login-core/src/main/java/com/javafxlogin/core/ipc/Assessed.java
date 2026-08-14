package com.javafxlogin.core.ipc;

import com.javafxlogin.core.policy.Assessment;
import java.util.Objects;

/**
 * What the policy made of the name and password an {@link Assess} carried.
 *
 * <p>It carries the {@link Assessment} itself rather than copying its two halves out: a second
 * record of the same shape here would be one more place for the two to disagree, and the policy's
 * answer is already the thing the client asked for.
 *
 * <p>An empty violation list means the pair would be accepted. The strength band comes back either
 * way: it is for the person to read, never a reason to refuse, and a client that treated a weak
 * band as a refusal would be enforcing a rule this system does not have.
 */
public record Assessed(Assessment assessment) implements Response {

  public Assessed {
    Objects.requireNonNull(assessment, "assessment");
  }
}
