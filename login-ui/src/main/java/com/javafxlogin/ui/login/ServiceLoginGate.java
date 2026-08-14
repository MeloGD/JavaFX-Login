package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.MalformedMessageException;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.ServiceClient;
import com.javafxlogin.core.session.Session;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The gate a shipped product runs behind: it asks the AuthenticationService and believes nothing
 * else.
 *
 * <p>It asks to act as an {@link Role#OPERATOR}, because that is the Role this gate exists to
 * admit, and it is the service that decides whether the Account holds it. An Administrator
 * authenticating here is refused, and refused over there — this class could not grant them access
 * if it wanted to, and a patched copy of it could not either.
 *
 * <p>One connection is opened on the first attempt and kept, because a Session is bound to its
 * connection: the Session granted at the end of a run of attempts lives on the connection those
 * attempts were made over, and closing it is what ends the Session. A client that dies has the
 * kernel do that for it.
 *
 * <p>Not thread-safe: the window makes one attempt at a time.
 */
final class ServiceLoginGate implements LoginGate {

  private final Path socketPath;

  private ServiceClient client;

  ServiceLoginGate(Path socketPath) {
    this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
  }

  @Override
  public Optional<Session> admit(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    Response response;
    try {
      response = connection().send(new Authenticate(accountName, password, Role.OPERATOR));
    } catch (IOException | MalformedMessageException e) {
      // Whether the socket broke or the answer could not be read, this connection is no longer
      // one anything may be believed over. The next attempt opens a fresh one.
      drop();
      throw new ServiceUnreachableException(
          "Could not reach the AuthenticationService at " + socketPath, e);
    }

    return switch (response) {
      case Granted granted -> Optional.of(new Session(granted.token()));
      case Denied ignored -> Optional.empty();
      // A readable answer that does not answer this question — a store the service cannot open,
      // say. It is not a refusal, and showing it as one would send the person to retype a
      // password that was never the problem.
      default ->
          throw new ServiceUnreachableException(
              "The AuthenticationService answered an authentication attempt with a "
                  + response.getClass().getSimpleName());
    };
  }

  private ServiceClient connection() throws IOException {
    if (client == null || !client.isOpen()) {
      client = ServiceClient.connect(socketPath);
    }
    return client;
  }

  private void drop() {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (IOException ignored) {
      // Already reporting a worse failure to the caller.
    } finally {
      client = null;
    }
  }
}
