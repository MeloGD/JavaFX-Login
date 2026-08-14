package com.javafxlogin.core.ipc;

/**
 * What the AuthenticationService answers with.
 *
 * <p>Every response carries the outcome and nothing more than the caller needs. In particular a
 * failed authentication distinguishes nothing about why it failed.
 */
public sealed interface Response
    permits Granted, Denied, Ok, Assessed, PolicyRefused, ErrorResponse {}
