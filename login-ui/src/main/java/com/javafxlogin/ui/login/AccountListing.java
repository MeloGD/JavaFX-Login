package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request for the Accounts of this deployment.
 *
 * <p>The list is what the administration panel is drawn from, and it is the only thing this system
 * ever says about an Account other than the one asking — which is why the request behind it is
 * refused in the privileged process rather than by the screen.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}.
 */
public sealed interface AccountListing permits AccountsSeen, AdministrationRefused {}
