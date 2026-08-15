package com.javafxlogin.core.harness;

import com.javafxlogin.core.ipc.ConnectionHandle;
import com.javafxlogin.core.ipc.Peer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The connection a request arrived on, with no socket under it: a peer the operating system either
 * names or does not, and a connection a test closes by hand.
 *
 * <p>Seam 2 is where a real handle is tested, against a real {@code AF_UNIX} socket. That the
 * handle is an interface is what lets Seam 1 assert every rule resting on who the peer is, and
 * everything a Session bound to a connection does when that connection goes, without needing a
 * machine whose group database says the right thing or a client to kill.
 *
 * <p>One of these is one client. Two requests sent over the same StubConnection are two requests
 * from the same client, which is what a Session bound to a connection is bound to.
 */
public final class StubConnection implements ConnectionHandle {

  private final Optional<Peer> peer;
  private final List<Runnable> closeListeners = new ArrayList<>();

  private boolean open = true;

  private StubConnection(Optional<Peer> peer) {
    this.peer = peer;
  }

  /** A connection the operating system names. */
  public static StubConnection from(Peer peer) {
    return new StubConnection(Optional.of(peer));
  }

  /** A connection whose peer the operating system will not name, as a platform without them. */
  public static StubConnection fromAnUnnamedPeer() {
    return new StubConnection(Optional.empty());
  }

  @Override
  public Optional<Peer> peer() {
    return peer;
  }

  @Override
  public synchronized boolean isOpen() {
    return open;
  }

  @Override
  public void whenClosed(Runnable listener) {
    synchronized (this) {
      if (open) {
        closeListeners.add(listener);
        return;
      }
    }
    listener.run();
  }

  /** What the kernel does for a client that died, without a client having to die. */
  public void close() {
    List<Runnable> listeners;
    synchronized (this) {
      if (!open) {
        return;
      }
      open = false;
      listeners = List.copyOf(closeListeners);
      closeListeners.clear();
    }
    listeners.forEach(Runnable::run);
  }
}
