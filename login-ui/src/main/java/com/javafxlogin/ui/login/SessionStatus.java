package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService says about a Session the SessionGuard is watching.
 *
 * <p>The guard reports and asks; this is everything it can be told back. It decides nothing from
 * either answer beyond when to ask again, which is the whole of the division of labour: expiry
 * belongs to the service, and a client that computed it for itself could be patched into computing
 * a later one.
 */
public sealed interface SessionStatus permits SessionContinues, SessionOver {}
