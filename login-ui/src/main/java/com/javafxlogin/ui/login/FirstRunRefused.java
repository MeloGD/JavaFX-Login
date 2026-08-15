package com.javafxlogin.ui.login;

import java.util.Objects;

/**
 * The wizard was not allowed to run at all, whatever was typed into it.
 *
 * <p>Neither reason is about the name or the password, so neither sends the person back to the
 * fields to try again. The window says which of the two it was, because the remedies have nothing
 * in common: one is over and done with, and the other needs the application started by somebody
 * else.
 */
public record FirstRunRefused(FirstRunRefusedReason reason) implements FirstRunOutcome {

  public FirstRunRefused {
    Objects.requireNonNull(reason, "reason");
  }
}
