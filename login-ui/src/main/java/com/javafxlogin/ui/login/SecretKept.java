package com.javafxlogin.ui.login;

/**
 * The secret was written to the SecretVault, under the name it was given.
 *
 * <p>It carries nothing. What a host product wanted was that the secret be kept, and afterwards it
 * is; a copy of what was written would be this system handing back the thing it was asked to put
 * away.
 */
public record SecretKept() implements SecretKeepingOutcome {}
