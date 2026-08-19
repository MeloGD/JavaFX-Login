package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request to copy the record of AuthenticationEvents out.
 *
 * <p>The copy is a file the Administrator reads with their own tools, and this is everything the
 * application learns about it: how much was copied, and whether the chain still held. Not one event
 * comes back, because nothing hands an event back to the application.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}.
 */
public sealed interface ExportOutcome permits EventsExported, AdministrationRefused {}
