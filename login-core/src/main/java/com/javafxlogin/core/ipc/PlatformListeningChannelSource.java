package com.javafxlogin.core.ipc;

import java.util.Locale;

/**
 * Chooses how this platform obtains its listening channel.
 *
 * <p>The two platforms do not share an activation trigger. Linux is socket-activated:
 * the socket always exists and connecting to it is what starts the service. Windows
 * keeps a Manual-start service that binds its own socket, so the client must start
 * the service and then wait for the socket to appear.
 *
 * <p>The Windows path is <strong>designed and unbuilt</strong>. No Windows machine
 * exists for this project yet, and a half-written path that no test can run is worse
 * than an honest refusal — so it refuses here and its ticket waits on hardware.
 */
public final class PlatformListeningChannelSource {

  private PlatformListeningChannelSource() {}

  /**
   * The source for the platform this process is running on.
   *
   * @throws UnsupportedOperationException on any platform whose acquisition path has
   *     not been built
   */
  public static ListeningChannelSource forCurrentPlatform() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (osName.contains("linux")) {
      return new InheritedListeningChannelSource();
    }
    if (osName.contains("windows")) {
      throw new UnsupportedOperationException(
          "The Windows acquisition path is designed but not built: the service must bind"
              + " its own socket inside an already-restricted directory, and the client"
              + " must start the service and wait for that socket to appear");
    }
    throw new UnsupportedOperationException(
        "No acquisition path for " + System.getProperty("os.name")
            + "; this product targets Windows 11 and Ubuntu with GNOME");
  }
}
