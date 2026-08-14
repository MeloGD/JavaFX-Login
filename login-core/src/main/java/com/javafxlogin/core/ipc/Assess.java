package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * Asks what the policy makes of a name and a password, without creating anything.
 *
 * <p>This is how the wizard shows a person the rules while they type. It exists so that the client
 * does not carry a copy of the rules: a second implementation would drift from the one that
 * actually decides, and the person would be told one thing and then refused for another.
 *
 * <p>Answering it grants nothing and reveals nothing about which Accounts exist — the answer is the
 * same whether or not the name is taken.
 *
 * @param accountName the name as typed so far, which may be empty
 * @param password the password as typed so far, not retained by the service
 */
public record Assess(String accountName, char[] password) implements Request {

  public Assess {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "Assess[redacted]";
  }
}
