package com.javafxlogin.core.harness;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.authentication.AuthenticationService;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;

import java.nio.file.Path;

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

    private final Path directory;
    private final Argon2Parameters parameters;
    private AuthenticationService service;

    private ServiceHarness(Path directory, Argon2Parameters parameters) {
        this.directory = directory;
        this.parameters = parameters;
        this.service = AuthenticationService.open(storeFile(), parameters);
    }

    /** A harness with cheap hashing parameters — the default for everything but the pinning tests. */
    public static ServiceHarness cheap(Path directory) {
        return new ServiceHarness(directory, CHEAP);
    }

    /** A harness with the given hashing parameters, for tests that care what they cost. */
    public static ServiceHarness with(Path directory, Argon2Parameters parameters) {
        return new ServiceHarness(directory, parameters);
    }

    public Response send(Request request) {
        return service.handle(request);
    }

    /** Creates the single Administrator, which almost every test needs before it can do anything. */
    public Response bootstrap(String administratorName, String password) {
        return send(new Bootstrap(administratorName, password.toCharArray()));
    }

    /** Closes and reopens the service against the same files, as a service restart would. */
    public void restart() {
        service.close();
        service = AuthenticationService.open(storeFile(), parameters);
    }

    public Path directory() {
        return directory;
    }

    public Path storeFile() {
        return storeFileIn(directory);
    }

    /** Where the store lives, for tests that must reach it without holding a harness. */
    public static Path storeFileIn(Path directory) {
        return directory.resolve("credentials.db");
    }

    @Override
    public void close() {
        service.close();
    }
}
