package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.DeniedReason;
import java.util.Objects;

/**
 * Nobody was admitted.
 *
 * <p>The reason is the service's own, carried through rather than interpreted here, and it is
 * almost always the one that says nothing: a wrong password, an Account that does not exist and an
 * Account holding another Role are one refusal, because the login screen must not become an oracle
 * for the account list. The exception is a Session already being live, which says nothing about any
 * Account and everything about why retyping a password would not help.
 */
public record NotAdmitted(DeniedReason reason) implements Admission {

  public NotAdmitted {
    Objects.requireNonNull(reason, "reason");
  }
}
