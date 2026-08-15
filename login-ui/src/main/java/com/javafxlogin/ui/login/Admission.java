package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of an attempt to reach the ProtectedFeature.
 *
 * <p>The set is closed for the same reason {@link FirstRunOutcome}'s is: the window has to say
 * something different for each, and an outcome nobody worded would reach a person as a blank
 * refusal. It is not the whole of what the service knows, and deliberately so — every reason an
 * authentication failed arrives as one {@link NotAdmitted}, because a reason a client can read is a
 * reason an attacker can read.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}, because the remedy is to get the service running rather than to
 * retype anything.
 */
public sealed interface Admission permits Admitted, NotAdmitted {}
