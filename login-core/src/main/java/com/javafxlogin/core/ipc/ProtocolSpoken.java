package com.javafxlogin.core.ipc;

/**
 * The version of the message catalogue the AuthenticationService speaks.
 *
 * <p>It says a number and nothing else — not what the service is, not what it holds, not whether it
 * has been set up. A build older or newer than the one asking learns only that the two of them
 * disagree, which is exactly what the person at the keyboard needs told and is all an unauthenticated
 * peer is owed.
 *
 * <p>Frozen along with the question, for the reason {@link ProtocolVersion} gives.
 *
 * @param version what the service speaks, to be compared against {@link ProtocolVersion#CURRENT}
 */
public record ProtocolSpoken(int version) implements Response {}
