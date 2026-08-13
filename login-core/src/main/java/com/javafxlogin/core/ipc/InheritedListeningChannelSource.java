package com.javafxlogin.core.ipc;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;

/**
 * The Linux half: adopt the listening channel systemd created and handed over under
 * socket activation.
 *
 * <p>{@code System.inheritedChannel()} is the whole mechanism, and the spike in
 * {@code docs/spikes/linux-service-activation.md} records the two traps behind it.
 * It inspects <em>only file descriptor 0</em>, while systemd passes the listening
 * socket as fd 3, so the {@code .service} unit needs {@code StandardInput=socket}
 * and the {@code .socket} unit exactly one {@code ListenStream=}. And
 * {@code $LISTEN_FDS} is deliberately not set in this mode, so activation is
 * detected by asking for the channel — never by reading that variable.
 *
 * <p>Nothing here is reflective and no {@code --add-opens} is needed: the channel is
 * only ever named as a {@link ServerSocketChannel}.
 */
public final class InheritedListeningChannelSource implements ListeningChannelSource {

  private final InheritedChannel inheritedChannel;

  public InheritedListeningChannelSource() {
    this(System::inheritedChannel);
  }

  /** Lets a test supply what systemd would have handed over. */
  InheritedListeningChannelSource(InheritedChannel inheritedChannel) {
    this.inheritedChannel = inheritedChannel;
  }

  /** What the JVM was handed on file descriptor 0, if anything. */
  @FunctionalInterface
  interface InheritedChannel {
    Channel get() throws IOException;
  }

  @Override
  public ServerSocketChannel acquire() throws IOException {
    Channel inherited = inheritedChannel.get();
    if (inherited == null) {
      throw new ListeningChannelUnavailableException(
          "No channel was inherited: this process was not started by socket activation");
    }
    if (!(inherited instanceof ServerSocketChannel listening)) {
      throw new ListeningChannelUnavailableException(
          "The inherited channel is a " + inherited.getClass().getSimpleName()
              + " rather than a listening socket; check that the socket unit declares"
              + " exactly one ListenStream=");
    }

    SocketAddress address = listening.getLocalAddress();
    if (address == null) {
      throw new ListeningChannelUnavailableException(
          "The inherited channel is not bound to anything, so nothing can connect to it");
    }
    if (!(address instanceof UnixDomainSocketAddress)) {
      throw new ListeningChannelUnavailableException(
          "The inherited channel listens on " + address
              + " rather than an AF_UNIX path; ADR-0003 refuses any other transport");
    }
    return listening;
  }

  /**
   * Deliberately does nothing. The socket file was created by systemd and outlives
   * this process — the socket unit stays listening after the service exits, which is
   * what makes the next connection reactivate it. Removing the file would break that.
   */
  @Override
  public void release() {}
}
