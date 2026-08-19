package com.javafxlogin.ui.login;

import java.util.Objects;

/**
 * No secret, and why — as far as a Session the service granted is told.
 *
 * <p>The one outcome both halves of the Vault share: a Session that may not read a secret may not
 * keep one either, and it is refused in the same words.
 *
 * @param reason the service's own, carried through rather than interpreted here
 */
public record SecretWithheld(SecretWithheldReason reason)
    implements SecretOutcome, SecretKeepingOutcome {

  public SecretWithheld {
    Objects.requireNonNull(reason, "reason");
  }
}
