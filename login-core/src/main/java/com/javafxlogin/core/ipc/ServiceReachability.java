package com.javafxlogin.core.ipc;

/**
 * What a client found when it went looking for the AuthenticationService.
 *
 * <p>It is asked once, before anything is drawn, because ADR-0002 makes the service the only party
 * that can verify a password: an application that opened its windows without one would be a login
 * screen that cannot log anybody in, and looking like a working gate is worse than plainly refusing
 * to be one.
 *
 * <p>What comes back is either {@link Reachable} or an {@link Unreachable} naming which of three
 * things went wrong, because the three have different remedies and a person told "something went
 * wrong" has been told nothing they can act on.
 *
 * @see ServiceHandshake for how the three are told apart
 */
public sealed interface ServiceReachability permits Reachable, Unreachable {}
