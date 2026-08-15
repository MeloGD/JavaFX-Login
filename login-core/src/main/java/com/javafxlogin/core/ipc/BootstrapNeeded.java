package com.javafxlogin.core.ipc;

/**
 * Whether this installation is still waiting for its single Administrator.
 *
 * <p>It says nothing about who the Administrator is, or would be. A client that is told the
 * bootstrap is needed has learnt that the CredentialStore holds no Administrator and nothing else —
 * in particular no name, which ADR-0002 keeps unreadable.
 *
 * @param needed true while no Administrator exists
 */
public record BootstrapNeeded(boolean needed) implements Response {}
