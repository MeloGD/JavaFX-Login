package com.javafxlogin.core.store;

/** Thrown when the CredentialStore cannot be opened, read or written. */
public class CredentialStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CredentialStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
