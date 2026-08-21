package com.javafxlogin.core.ipc;

/**
 * The AuthenticationService answered, and it speaks this build's protocol.
 *
 * <p>It carries nothing. Having been answered once is not a promise about the next request — the
 * service exits after its idle period and is activated again by the connection that needs it, so
 * what this says is that the socket is there, reachable by this account, and behind it is something
 * that agrees with this build about what the messages mean.
 */
public record Reachable() implements ServiceReachability {}
