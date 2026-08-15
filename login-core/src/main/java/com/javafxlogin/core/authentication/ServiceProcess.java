package com.javafxlogin.core.authentication;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.ListeningChannelSource;
import com.javafxlogin.core.ipc.PlatformListeningChannelSource;
import com.javafxlogin.core.ipc.TransportServer;
import com.javafxlogin.core.machine.MachineAdministrators;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * The privileged process: a CredentialStore, the service that owns it, and the channel clients
 * reach it on, started together and stopped together.
 *
 * <p>This is the whole of the deployment shape. Which channel it serves on is the one thing the
 * platforms disagree about, and that disagreement lives behind {@link ListeningChannelSource}, so
 * running under systemd rather than binding a socket is a different argument here and no different
 * code anywhere else.
 */
public final class ServiceProcess implements AutoCloseable {

  private static final String USAGE =
      "Usage: ServiceProcess <credential-store-file> [--socket <path>]";

  private final AuthenticationService service;
  private final TransportServer server;

  private ServiceProcess(AuthenticationService service, TransportServer server) {
    this.service = service;
    this.server = server;
  }

  /** Opens the store and starts serving, hashing at {@link Argon2Parameters#PRODUCTION}. */
  public static ServiceProcess start(ListeningChannelSource source, Path storeFile)
      throws IOException {
    return start(source, storeFile, Argon2Parameters.PRODUCTION);
  }

  /**
   * As {@link #start(ListeningChannelSource, Path)}, with the hashing parameters named explicitly,
   * so that a test can provision Accounts cheaply without changing the path being tested.
   */
  public static ServiceProcess start(
      ListeningChannelSource source, Path storeFile, Argon2Parameters parameters)
      throws IOException {
    return start(source, storeFile, parameters, MachineAdministrators.forCurrentPlatform());
  }

  /**
   * As {@link #start(ListeningChannelSource, Path, Argon2Parameters)}, with the machine's
   * administrators named explicitly, so that a test driving a real socket can decide whether the
   * account it runs as may create the Administrator instead of inheriting the developer's groups.
   */
  public static ServiceProcess start(
      ListeningChannelSource source,
      Path storeFile,
      Argon2Parameters parameters,
      MachineAdministrators administrators)
      throws IOException {
    Objects.requireNonNull(source, "source");
    AuthenticationService service =
        AuthenticationService.open(storeFile, parameters, administrators);
    try {
      TransportServer server = TransportServer.start(source, new ServiceEndpoint(service));
      return new ServiceProcess(service, server);
    } catch (IOException | RuntimeException e) {
      // Nothing can reach the store if the channel could not be served on, so it is closed rather
      // than left open by a process that is about to refuse to start.
      service.close();
      throw e;
    }
  }

  /**
   * Runs the service until the process is asked to stop.
   *
   * <p>With no {@code --socket}, the channel is the one this platform hands over: on Linux the
   * listening socket systemd created, which is what makes connecting to it start this process.
   * {@code --socket} binds one instead, which is how the service is run by hand on a development
   * machine and how the Windows service will run once it exists.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length != 1 && args.length != 3) {
      throw new IllegalArgumentException(USAGE);
    }
    Path storeFile = Path.of(args[0]);
    ListeningChannelSource source =
        args.length == 1
            ? PlatformListeningChannelSource.forCurrentPlatform()
            : boundToTheSocketNamedIn(args);

    ServiceProcess process = start(source, storeFile);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  process.close();
                  stopped.countDown();
                },
                "service-shutdown"));
    stopped.await();
  }

  private static ListeningChannelSource boundToTheSocketNamedIn(String[] args) {
    if (!"--socket".equals(args[1])) {
      throw new IllegalArgumentException(USAGE);
    }
    return new BoundListeningChannelSource(Path.of(args[2]));
  }

  /** Stops serving and closes the store, in that order: no request outlives the channel. */
  @Override
  public void close() {
    try {
      server.close();
    } catch (IOException e) {
      // The channel is being given up either way, and the store still has to be closed.
    } finally {
      service.close();
    }
  }
}
