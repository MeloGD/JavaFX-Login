package com.javafxlogin.core.harness;

import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.ipc.Peer;
import java.util.Optional;

/**
 * The connection a request arrived on, with no socket under it: a peer the operating system either
 * names or does not, and a connection that never closes.
 *
 * <p>Seam 2 is where a real handle is tested, against a real {@code AF_UNIX} socket. That the
 * handle is an interface is what lets Seam 1 assert every rule resting on who the peer is without
 * needing a machine whose group database says the right thing.
 */
public record StubConnection(Optional<Peer> peer) implements ConnectionHandle {

  /** A connection the operating system names. */
  public static StubConnection from(Peer peer) {
    return new StubConnection(Optional.of(peer));
  }

  /** A connection whose peer the operating system will not name, as a platform without them. */
  public static StubConnection fromAnUnnamedPeer() {
    return new StubConnection(Optional.empty());
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  @Override
  public void whenClosed(Runnable listener) {
    // Nothing here ever closes, so no listener would ever run.
  }
}
