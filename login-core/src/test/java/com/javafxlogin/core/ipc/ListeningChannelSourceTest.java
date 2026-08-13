package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one component where Linux and Windows diverge: acquiring the listening
 * channel. Everything above it is shared, so this is the only place a platform is
 * allowed to matter.
 *
 * <p>What systemd itself does is not testable in a unit test and is covered by the
 * manual checklist recorded in {@code docs/spikes/linux-service-activation.md}.
 * What is tested here is the adoption logic: given what systemd would hand over,
 * the service takes it — and given anything else, it refuses rather than serving
 * over a channel it was not promised.
 */
class ListeningChannelSourceTest {

  @TempDir Path runtimeDirectory;

  @Test
  void adoptsAnInheritedUnixDomainListeningChannel() throws Exception {
    Path socketPath = runtimeDirectory.resolve("inherited.sock");
    try (ServerSocketChannel systemdWouldPass = boundUnixChannel(socketPath)) {
      ListeningChannelSource source = new InheritedListeningChannelSource(() -> systemdWouldPass);

      assertSame(systemdWouldPass, source.acquire());
    }
  }

  @Test
  void refusesToServeWhenNothingWasInherited() {
    ListeningChannelSource source = new InheritedListeningChannelSource(() -> null);

    assertThrows(ListeningChannelUnavailableException.class, source::acquire);
  }

  @Test
  void refusesAnInheritedChannelThatIsNotAUnixDomainSocket() throws Exception {
    try (ServerSocketChannel loopback = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
      loopback.bind(new InetSocketAddress("127.0.0.1", 0));
      ListeningChannelSource source = new InheritedListeningChannelSource(() -> loopback);

      assertThrows(ListeningChannelUnavailableException.class, source::acquire);
    }
  }

  @Test
  void refusesAnInheritedChannelThatWasNeverBound() throws Exception {
    try (ServerSocketChannel unbound = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      ListeningChannelSource source = new InheritedListeningChannelSource(() -> unbound);

      assertThrows(ListeningChannelUnavailableException.class, source::acquire);
    }
  }

  @Test
  void refusesAnInheritedChannelThatIsNotAListeningSocketAtAll() throws Exception {
    Pipe pipe = Pipe.open();
    try (Channel notASocket = pipe.source()) {
      ListeningChannelSource source = new InheritedListeningChannelSource(() -> notASocket);

      assertThrows(ListeningChannelUnavailableException.class, source::acquire);
    } finally {
      pipe.sink().close();
    }
  }

  @Test
  void bindsItsOwnListeningChannelAtTheGivenPath() throws Exception {
    Path socketPath = runtimeDirectory.resolve("bound.sock");
    ListeningChannelSource source = new BoundListeningChannelSource(socketPath);

    try (ServerSocketChannel channel = source.acquire()) {
      assertTrue(Files.exists(socketPath));
      assertEquals(UnixDomainSocketAddress.of(socketPath), channel.getLocalAddress());
    }
  }

  @Test
  void refusesToBindOverAPathThatIsAlreadyTaken() throws Exception {
    Path socketPath = runtimeDirectory.resolve("taken.sock");
    Files.writeString(socketPath, "not a socket");

    ListeningChannelSource source = new BoundListeningChannelSource(socketPath);

    assertThrows(IOException.class, source::acquire);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void onLinuxTheServiceExpectsTheChannelToBeHandedToItBySystemd() {
    ListeningChannelSource source = PlatformListeningChannelSource.forCurrentPlatform();

    assertNotNull(source);
    // This process was not socket-activated, so acquisition must say exactly that
    // rather than quietly binding a socket of its own.
    ListeningChannelUnavailableException refusal =
        assertThrows(ListeningChannelUnavailableException.class, source::acquire);
    assertTrue(refusal.getMessage().contains("socket activation"), refusal.getMessage());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void onWindowsAcquisitionIsNotImplementedYet() {
    // Deliberately guarded so it skips on Linux rather than passing on Linux: the
    // Windows half is designed and unbuilt, and no Windows machine exists for this
    // project yet.
    assertThrows(
        UnsupportedOperationException.class, PlatformListeningChannelSource::forCurrentPlatform);
  }

  private static ServerSocketChannel boundUnixChannel(Path socketPath) throws IOException {
    ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socketPath));
    return channel;
  }
}
