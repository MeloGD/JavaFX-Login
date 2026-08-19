package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of an administration request that carries nothing back: a
 * deleted Account, a cleared Lockout, a changed InactivityPeriod.
 *
 * <p>Closed, like every other outcome a caller of this gate is handed, and short because there are
 * only two things to say: it was done, or it was refused and here is which refusal. The requests
 * that come back with something — an EnrolmentSecret, a list of Accounts, the size of an export —
 * answer with sets of their own, so that no caller has to handle a case another request produces.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}, as it is everywhere else on the {@link LoginGate}.
 */
public sealed interface AdministrationOutcome permits Administered, AdministrationRefused {}
