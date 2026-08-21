package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request to write a Backup.
 *
 * <p>Apart from {@link RestoreOutcome} for the reason {@link SecretOutcome} is apart from {@link
 * SecretKeepingOutcome}: reading and writing are two questions, they are asked by different clicks,
 * and what happens on this side afterwards is not the same. A Backup that was written leaves the
 * panel where it was; one that was restored leaves the person at the login screen of a deployment
 * they have just replaced.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}.
 */
public sealed interface BackupOutcome permits BackupWritten, AdministrationRefused {}
