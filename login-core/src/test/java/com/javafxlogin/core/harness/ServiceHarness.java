package com.javafxlogin.core.harness;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.authentication.AuthenticationService;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.Peer;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.store.CredentialStore;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Seam 1: the AuthenticationService request handler, in process.
 *
 * <p>Builds a service whose CredentialStore lives inside a JUnit {@code @TempDir}, then hands it
 * request objects and returns the response objects that come back. No socket is involved.
 *
 * <p>The service is privileged in production, but privilege is a deployment property rather than a
 * behavioural one: running the same code unprivileged against a temporary directory exercises every
 * rule the service enforces.
 */
public final class ServiceHarness implements AutoCloseable {

  /**
   * Deliberately cheap Argon2id parameters. Production parameters cost 50-100 ms per hash by
   * design, which is ruinous in a suite that authenticates hundreds of times. Because parameters
   * travel inside the PHC hash string, an Account provisioned with these still goes through the
   * identical verification path.
   */
  public static final Argon2Parameters CHEAP = new Argon2Parameters(256, 1, 1, 32);

  /**
   * Seam 1's stand-in for the machine's group database: a peer whose primary group is this one
   * administers the machine, and nobody else does. Which groups a real machine calls
   * administrative is {@code PosixMachineAdministrators}' business and is tested there.
   */
  private static final String ADMINISTRATIVE_GROUP = "sudo";

  /** The peer requests arrive from unless a test says otherwise: the person installing. */
  public static final Peer INSTALLING_PEER = new Peer("juno.vale", ADMINISTRATIVE_GROUP);

  /** Someone with an account on the machine and no business administering it. */
  public static final Peer ORDINARY_PEER = new Peer("mallory.quill", "mallory.quill");

  /**
   * Where the clocks start. A fixed instant rather than the machine's own, so that a test that
   * moves them describes what it did rather than what the developer's afternoon was.
   */
  private static final Instant FIRST_LIGHT = Instant.parse("2026-03-01T09:00:00Z");

  private final Path directory;
  private final Argon2Parameters parameters;
  private final TickingClock clock = TickingClock.startingAt(FIRST_LIGHT);
  private final StubConnection connection = StubConnection.from(INSTALLING_PEER);

  private AuthenticationService service;

  private ServiceHarness(Path directory, Argon2Parameters parameters) {
    this.directory = directory;
    this.parameters = parameters;
    this.service = openService();
  }

  /** A harness with cheap hashing parameters — the default for everything but the pinning tests. */
  public static ServiceHarness cheap(Path directory) {
    return new ServiceHarness(directory, CHEAP);
  }

  /** A harness with the given hashing parameters, for tests that care what they cost. */
  public static ServiceHarness with(Path directory, Argon2Parameters parameters) {
    return new ServiceHarness(directory, parameters);
  }

  /**
   * Sends a request as the person installing, which is who almost every test is, over this
   * harness's one connection — so that a run of requests is one client, as it is in production.
   */
  public Response send(Request request) {
    return sendOver(connection, request);
  }

  /** Sends a request as a named peer, for the tests about who may create the Administrator. */
  public Response sendFrom(Peer peer, Request request) {
    return sendOver(StubConnection.from(peer), request);
  }

  /** Sends a request from a named client, for the tests about a Session bound to a connection. */
  public Response sendOver(StubConnection connection, Request request) {
    return service.handle(request, connection);
  }

  /** This harness's connection, for a test that closes it as a dying client would. */
  public StubConnection connection() {
    return connection;
  }

  /** A second client of the same service, with a connection of its own. */
  public StubConnection anotherConnection() {
    return StubConnection.from(INSTALLING_PEER);
  }

  /** The clocks the service times Sessions against, for a test to move. */
  public TickingClock clock() {
    return clock;
  }

  /** Configures how long a Session may idle, without going through an Administrator to do it. */
  public void inactivityPeriodIs(InactivityPeriod period) {
    try (CredentialStore store = CredentialStore.openOrCreate(storeFile())) {
      store.setInactivityPeriod(period);
    }
  }

  /**
   * Sends a request over a connection whose peer the operating system will not name, which is what
   * a platform without peer credentials looks like from in here.
   */
  public Response sendFromAnUnnamedPeer(Request request) {
    return service.handle(request, StubConnection.fromAnUnnamedPeer());
  }

  /** Creates the single Administrator, which almost every test needs before it can do anything. */
  public Response bootstrap(String administratorName, String password) {
    return send(new Bootstrap(administratorName, password.toCharArray()));
  }

  /**
   * Writes an Operator straight into the CredentialStore, hashed with this harness's parameters.
   *
   * <p>It goes round the service because no request creates an Operator yet: an Administrator
   * provisions one and never chooses their password, which is issue #10's enrolment. Until then a
   * test that needs someone who may reach the ProtectedFeature has to put them there itself. The
   * store is opened and closed again around the insert, so the service's own connection keeps
   * owning the file for the rest of the test.
   */
  public void provisionOperator(String name, String password) {
    provisionOperatorIn(directory, parameters, name, password);
  }

  /** The same, for a test that owns the store's directory but holds no harness over it. */
  public static void provisionOperatorIn(
      Path directory, Argon2Parameters parameters, String name, String password) {
    String hash = new Authenticator(parameters).hash(password.toCharArray());
    try (CredentialStore store = CredentialStore.openOrCreate(storeFileIn(directory))) {
      store.insert(new Account(name, Role.OPERATOR, hash, PasswordStrength.ACCEPTABLE));
    }
  }

  /** Closes and reopens the service against the same files, as a service restart would. */
  public void restart() {
    service.close();
    service = openService();
  }

  private AuthenticationService openService() {
    return AuthenticationService.open(
        storeFile(),
        parameters,
        peer -> ADMINISTRATIVE_GROUP.equals(peer.primaryGroupName()),
        clock);
  }

  private Path storeFile() {
    return storeFileIn(directory);
  }

  /** Where the store lives, for tests that must reach it without holding a harness. */
  public static Path storeFileIn(Path directory) {
    return directory.resolve("credentials.db");
  }

  /** Where the AuthenticationEvents the service recorded are written. */
  public static Path eventLogIn(Path directory) {
    return directory.resolve("authentication-events.csv");
  }

  @Override
  public void close() {
    service.close();
  }
}
