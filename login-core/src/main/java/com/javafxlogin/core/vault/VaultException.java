package com.javafxlogin.core.vault;

/** Thrown when the SecretVault cannot be opened, read or written. */
public final class VaultException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public VaultException(String message) {
    super(message);
  }

  public VaultException(String message, Throwable cause) {
    super(message, cause);
  }
}
