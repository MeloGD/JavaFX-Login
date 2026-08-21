package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request to restore a Backup.
 *
 * <p>The refusals are the ones a {@link BackupOutcome} carries and three more of its own — a file
 * that would not open, one from another version of the product, and one that names no
 * Administrator — because reading a file somebody chose can fail in ways writing one cannot.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}.
 */
public sealed interface RestoreOutcome permits BackupRestored, AdministrationRefused {}
