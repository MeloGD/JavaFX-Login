package com.javafxlogin.core.store;

/**
 * Thrown when the CredentialStore was written by a build that understood a later schema than this
 * one. The service refuses to start rather than write into a shape it cannot reason about, because
 * a downgrade that half-works corrupts the store instead of failing.
 */
public final class SchemaTooNewException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int foundVersion;
  private final int understoodVersion;

  public SchemaTooNewException(int foundVersion, int understoodVersion) {
    super(
        "the CredentialStore is at schema version "
            + foundVersion
            + ", but this build understands only version "
            + understoodVersion);
    this.foundVersion = foundVersion;
    this.understoodVersion = understoodVersion;
  }

  /** The schema version found in the store. */
  public int foundVersion() {
    return foundVersion;
  }

  /** The highest schema version this build understands. */
  public int understoodVersion() {
    return understoodVersion;
  }
}
