package com.javafxlogin.core.authentication;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.ipc.BoundListeningChannelSource;
import com.javafxlogin.core.ipc.ListeningChannelSource;
import com.javafxlogin.core.ipc.PlatformListeningChannelSource;
import com.javafxlogin.core.ipc.TransportServer;
import com.javafxlogin.core.machine.MachineAdministrators;
import com.javafxlogin.core.store.SchemaTooNewException;
import java.io.IOException;
import java.nio.file.Files;
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
      "Usage: ServiceProcess <credential-store-file> [--socket <path> | --upgrade]";

  /** The argument an installer runs this under, having just replaced one build with another. */
  private static final String UPGRADE = "--upgrade";

  /** What {@link #bringTheFilesUpToDate(Path)} answers where there is no CredentialStore yet. */
  private static final int NO_STORE_YET = 0;

  private final AuthenticationService service;
  private final TransportServer server;

  private volatile boolean closed;

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
   * Runs the service until nobody is using it, or until the process is asked to stop.
   *
   * <p>With no {@code --socket}, the channel is the one this platform hands over: on Linux the
   * listening socket systemd created, which is what makes connecting to it start this process.
   * {@code --socket} binds one instead, which is how the service is run by hand on a development
   * machine and how the Windows service will run once it exists.
   *
   * <p>{@code --upgrade} serves nothing and migrates instead: it is what a package runs after it
   * has replaced one build with another, and it is described on {@link #bringTheFilesUpToDate}.
   *
   * <p>Exiting when idle is not a failure and is reported as none: the socket belongs to systemd
   * and stays listening, so the next peer to connect starts this process again. Diagnostics go to
   * the journal by way of the standard error stream, which the {@code .service} unit sends
   * there — never to standard output, which under {@code StandardInput=socket} is one careless
   * unit-file line away from being the peer's own connection.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length == 0) {
      throw new IllegalArgumentException(USAGE);
    }
    Path storeFile = Path.of(args[0]);
    if (args.length == 2 && UPGRADE.equals(args[1])) {
      upgradeAndReport(storeFile);
      return;
    }
    if (args.length != 1 && args.length != 3) {
      throw new IllegalArgumentException(USAGE);
    }
    ListeningChannelSource source =
        args.length == 1
            ? PlatformListeningChannelSource.forCurrentPlatform()
            : boundToTheSocketNamedIn(args);

    ServiceProcess process = start(source, storeFile);
    process.serveUntilNobodyIsUsingIt();
  }

  /**
   * Brings the files this process owns up to the schema this build understands, and answers the
   * version the CredentialStore is now at — or {@code 0} where there is no CredentialStore yet.
   *
   * <p>Opening those files is what migrates them, so this opens them and closes them again and is
   * nothing more than that. What it is <em>for</em> is when it runs: an installer replacing one
   * build with another can do this while somebody is watching, and under socket activation nobody
   * is watching afterwards. A migration left to the first activation fails into a login screen
   * saying the service is not running — which is also what a service that started perfectly well
   * and was never connected to looks like — in front of a person who was told minutes ago that the
   * installation succeeded.
   *
   * <p>A machine with no CredentialStore is left with none. An upgrade is not what creates a
   * deployment: the FirstRunWizard is, and a store, a SecretVault and an event log written by an
   * installer would make a machine nobody has logged in to look like one somebody has.
   *
   * @throws SchemaTooNewException if either file was written by a build that understood more, in
   *     which case nothing is written to it — the remedy is the build that wrote it, not this one
   */
  public static int bringTheFilesUpToDate(Path storeFile) {
    Objects.requireNonNull(storeFile, "storeFile");
    if (!Files.exists(storeFile)) {
      return NO_STORE_YET;
    }
    try (AuthenticationService service = AuthenticationService.open(storeFile)) {
      return service.schemaVersion();
    }
  }

  /**
   * The {@code --upgrade} mode: migrate, say what was found, and fail the installation that ran it
   * where the files are from a later build than this one.
   *
   * <p>Both lines go to the standard error stream, like every other thing this process says. The
   * rule is worth more than the exception would be: standard output is one careless unit-file line
   * away from being a client's own connection, and a rule with a mode-shaped hole in it is one
   * somebody has to hold in their head.
   */
  private static void upgradeAndReport(Path storeFile) {
    int version;
    try {
      version = bringTheFilesUpToDate(storeFile);
    } catch (SchemaTooNewException e) {
      System.err.println(e.getMessage());
      System.exit(1);
      return;
    }
    System.err.println(
        version == NO_STORE_YET
            ? "there is no CredentialStore at " + storeFile + ", and an upgrade does not make one"
            : "the CredentialStore at " + storeFile + " is at schema version " + version);
  }

  /**
   * Serves until the idle period passes with nothing going on, or until the machine asks this
   * process to stop, and closes the store on the way out either way.
   *
   * <p>The two routes out are the same route: whichever arrives first releases the latch, and
   * {@link #close()} runs once because it is the only thing that closes anything.
   */
  public void serveUntilNobodyIsUsingIt() throws InterruptedException {
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  close();
                  stopped.countDown();
                },
                "service-shutdown"));
    try (IdleShutdown idle = IdleShutdown.startWatching(this::inUse, stopped::countDown)) {
      stopped.await();
    } finally {
      close();
    }
  }

  /**
   * Whether anything is going on: a peer still connected, or a Session still live.
   *
   * <p>The connection is asked about first, and not only because it is the cheaper question. A
   * Session cannot outlive the connection it was granted on, so a live Session with no live
   * connection is a state this process does not have — and asking the service costs its monitor,
   * which a request being answered is holding.
   */
  boolean inUse() {
    return server.anyConnectionLive() || service.anySessionLive();
  }

  private static ListeningChannelSource boundToTheSocketNamedIn(String[] args) {
    if (!"--socket".equals(args[1])) {
      throw new IllegalArgumentException(USAGE);
    }
    return new BoundListeningChannelSource(Path.of(args[2]));
  }

  /**
   * Stops serving and closes the store, in that order: no request outlives the channel.
   *
   * <p>Does nothing the second time. Two things end this process — the idle period running out
   * and the machine asking it to stop — and they can arrive together.
   */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      server.close();
    } catch (IOException e) {
      // The channel is being given up either way, and the store still has to be closed.
    } finally {
      service.close();
    }
  }
}
