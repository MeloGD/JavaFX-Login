package com.javafxlogin.core.authentication;

import com.javafxlogin.core.account.Account;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.auth.Authenticator;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Request;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.session.SessionToken;
import com.javafxlogin.core.store.CredentialStore;
import com.javafxlogin.core.store.CredentialStoreException;
import com.javafxlogin.core.store.SchemaTooNewException;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * The privileged process: it owns the CredentialStore and is the only party that can verify a
 * password.
 *
 * <p>This class is the request handler, addressed in process. Nothing here knows about sockets; the
 * transport hands it request objects and returns what comes back. That is also what makes the
 * handler testable — running as root changes which files may be opened, and changes nothing about
 * whether a wrong password is refused.
 *
 * <p>Not to be confused with the {@link Authenticator}, which is the component that verifies a
 * password. The names are distinct on purpose.
 */
public final class AuthenticationService implements AutoCloseable {

    private final CredentialStore store;
    private final Authenticator authenticator;
    private final SecureRandom random;

    private AuthenticationService(CredentialStore store, Authenticator authenticator) {
        this.store = store;
        this.authenticator = authenticator;
        this.random = new SecureRandom();
    }

    /**
     * Opens the service as it ships: hashing at {@link Argon2Parameters#PRODUCTION}. This is the
     * overload production code calls, so that reaching the OWASP minimums is the default rather than
     * something every caller has to remember.
     *
     * @throws SchemaTooNewException if the store was written by a build that understood a later
     *                               schema — the service refuses to start rather than corrupt it
     */
    public static AuthenticationService open(Path storeFile) {
        return open(storeFile, Argon2Parameters.PRODUCTION);
    }

    /**
     * As {@link #open(Path)}, with the hashing parameters named explicitly. Tests use this to
     * provision Accounts cheaply; the verification path is the same either way, because the
     * parameters travel inside each stored PHC hash.
     */
    public static AuthenticationService open(Path storeFile, Argon2Parameters parameters) {
        Objects.requireNonNull(storeFile, "storeFile");
        Objects.requireNonNull(parameters, "parameters");

        CredentialStore store = CredentialStore.openOrCreate(storeFile);
        try {
            return new AuthenticationService(store, new Authenticator(parameters));
        } catch (RuntimeException e) {
            store.close();
            throw e;
        }
    }

    /**
     * Answers a request. Every request is answered: a store that cannot be read becomes an
     * {@link ErrorResponse} rather than an exception thrown at whatever is carrying the request,
     * because the caller is owed an outcome and must not be told which failure produced it.
     */
    public Response handle(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            return switch (request) {
                case Bootstrap bootstrap -> bootstrap(bootstrap);
                case Authenticate authenticate -> authenticate(authenticate);
            };
        } catch (CredentialStoreException e) {
            return new ErrorResponse(ErrorCode.STORE_UNAVAILABLE);
        }
    }

    private Response bootstrap(Bootstrap request) {
        if (store.hasAdministrator()) {
            return new ErrorResponse(ErrorCode.ADMINISTRATOR_EXISTS);
        }
        String hash = authenticator.hash(request.password());
        store.insert(new Account(request.administratorName(), Role.ADMINISTRATOR, hash));
        return new Ok();
    }

    private Response authenticate(Authenticate request) {
        Optional<Account> account = store.findByName(request.accountName());

        // The absent branch spends the same Argon2id work as the present one, so a stopwatch at the
        // login screen cannot name which Accounts are real.
        boolean verified = account
                .map(found -> authenticator.verify(request.password(), found.passwordHash()))
                .orElseGet(() -> authenticator.verifyAgainstAbsentAccount(request.password()));

        if (!verified) {
            return new Denied(DeniedReason.AUTH_FAILED);
        }
        return new Granted(SessionToken.generate(random), account.orElseThrow().role());
    }

    @Override
    public void close() {
        store.close();
    }
}
