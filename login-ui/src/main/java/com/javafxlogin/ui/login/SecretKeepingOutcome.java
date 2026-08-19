package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a secret offered to the SecretVault to keep.
 *
 * <p>Two outcomes and no more: it was written, or it was withheld for one of the reasons a Session
 * is refused the Vault. Kept apart from {@link SecretOutcome} so that a ProtectedFeature reading a
 * secret never has to handle "it was kept", which asking for one cannot produce.
 */
public sealed interface SecretKeepingOutcome permits SecretKept, SecretWithheld {}
