package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * The operating-system account running the process at the other end of a connection.
 *
 * <p>The kernel answers this, not the peer. The names come from the credentials attached to the
 * socket when it was connected, so nothing a client sends takes part in it and a patched client
 * cannot claim to be someone else. That is what makes an authorisation resting on this answer worth
 * having at all.
 *
 * <p>It carries names rather than numeric ids, because that is what the platform hands over, and it
 * carries only the primary group: every other group a peer belongs to is a lookup in the machine's
 * group database rather than a fact of the connection.
 */
public record Peer(String userName, String primaryGroupName) {

  public Peer {
    Objects.requireNonNull(userName, "userName");
    Objects.requireNonNull(primaryGroupName, "primaryGroupName");
  }
}
