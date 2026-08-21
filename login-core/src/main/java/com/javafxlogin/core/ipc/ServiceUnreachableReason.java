package com.javafxlogin.core.ipc;

/**
 * Why the AuthenticationService could not be asked.
 *
 * <p>Three of them, and no more, because these are the three that have different remedies: start
 * the service, install a matching version, or be put in the group that may reach the socket. A
 * fourth constant would only be worth adding when there is a fourth thing a person could do.
 *
 * <p>Nothing here says anything about the deployment — whether it has been set up, which Accounts
 * it holds, or what any of them are called. Everything below is decided before a single message
 * about an Account has been exchanged, and is told to a peer who has proved nothing.
 */
public enum ServiceUnreachableReason {

  /**
   * Nothing was listening, or nothing answered in time.
   *
   * <p>Under Linux socket activation the socket is always there — systemd created it, and
   * connecting is what starts the service — so "not running" is not something a client can observe
   * directly. What it observes instead is that the connection was accepted and the handshake was
   * never answered, which is what a service that failed to start looks like from out here. That is
   * a consequence of ADR-0002 choosing socket activation rather than a defect, and it is why the
   * handshake is given a deadline at all.
   *
   * <p>A socket path that does not exist lands here too, for the same reason it would be told the
   * same way: on a machine where the socket unit was never installed or enabled, the service cannot
   * be started, which is exactly what this says.
   */
  NOT_RUNNING,

  /**
   * Something answered, and it does not speak this build's catalogue.
   *
   * <p>Either it named a {@link ProtocolVersion} other than this build's, or what it sent back was
   * not a message this build reads at all. Both are the same fact — the two halves of this product
   * are not from the same release — and both are said as that rather than as a parse failure, which
   * is what they would otherwise look like from the far side of {@link MessageCodec}.
   */
  INCOMPATIBLE_VERSION,

  /**
   * The socket is there and the operating system refused this account access to it.
   *
   * <p>Typically a group membership: ADR-0003 has the socket's owner, group and mode set
   * declaratively, so the person at the keyboard is not in the group the installer created. It is
   * told apart from the two above because it is the one of the three the person can usually fix
   * without touching the installation.
   */
  SOCKET_NOT_ACCESSIBLE
}
