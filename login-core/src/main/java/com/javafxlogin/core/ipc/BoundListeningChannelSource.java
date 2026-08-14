package com.javafxlogin.core.ipc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Binds a listening {@code AF_UNIX} channel at a path of its own.
 *
 * <p>Used by the Seam 2 tests, which put a socket in a temporary directory. It is
 * also the shape the Windows service will need, since
 * {@code System.inheritedChannel()} is a Unix mechanism and returns {@code null}
 * there.
 *
 * <p>ADR-0003's warning applies to anything that binds its own socket: the socket
 * inherits {@code umask} permissions at {@code bind()}, so in production it must be
 * created inside an already-restricted directory rather than chmod'ed afterwards.
 * That caveat does not bind on Linux, where systemd creates the socket and its mode
 * is declarative.
 */
public final class BoundListeningChannelSource implements ListeningChannelSource {

  private final Path socketPath;

  public BoundListeningChannelSource(Path socketPath) {
    this.socketPath = socketPath;
  }

  @Override
  public ServerSocketChannel acquire() throws IOException {
    ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      listening.bind(UnixDomainSocketAddress.of(socketPath));
    } catch (IOException e) {
      listening.close();
      throw e;
    }
    return listening;
  }

  /** Removes the socket file this source created, so the next bind is not refused. */
  @Override
  public void release() throws IOException {
    Files.deleteIfExists(socketPath);
  }
}
