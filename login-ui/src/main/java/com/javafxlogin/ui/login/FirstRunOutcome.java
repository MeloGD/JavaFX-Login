package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of an attempt to create the single Administrator.
 *
 * <p>The set is closed because the wizard has to say something different for each of them, and
 * because it must be impossible to add an outcome the window silently ignores. Being specific is
 * safe here in a way it is not for a failed authentication: this answers the person setting the
 * machine up, and none of these outcomes says anything about an Account that already exists.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}, because the remedy is to get the service running rather than to
 * retype anything.
 */
public sealed interface FirstRunOutcome
    permits AdministratorCreated, PolicyRefusal, WizardRefused {}
