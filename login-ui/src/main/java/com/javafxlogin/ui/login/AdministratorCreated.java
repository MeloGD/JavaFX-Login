package com.javafxlogin.ui.login;

/**
 * The single Administrator now exists, and this installation will never run the wizard again.
 *
 * <p>It carries nothing. No recovery key, no backup code and no token is issued here, because none
 * is issued anywhere: the password the person just chose is the only way back in.
 */
public record AdministratorCreated() implements FirstRunOutcome {}
