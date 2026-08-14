# JavaFX Login

A JavaFX login window.

An offline login template that gates a host product's feature behind an `Account` and a password.
A privileged `AuthenticationService` owns every credential file and is the only party that can
verify a password; the graphical application runs unprivileged and asks it over a Unix domain
socket. `CONTEXT.md` has the language, `docs/adr/` has the decisions.

## Building

```bash
mvn test
```

Everything runs headless: no display is needed, and no test needs privileges.

## Integrating

A host product depends on `login-ui` and calls the `LoginGate`. That is the whole interface:

```java
LoginGate.toService(socketPath).protect(stage, () -> myFeatureView());
```

The gate shows the login window, closes it once an `Operator` is admitted, and opens the view it
was handed on a stage of its own. It never learns what that view is. The `protected-feature`
module is a working example of exactly this, and nothing more — including the launcher class that
starting a JavaFX application from the classpath requires.

## Running the pair by hand

The service and the application are two processes that agree on a socket path:

```bash
mvn -q package -DskipTests dependency:build-classpath \
  -Dmdep.outputFile=target/classpath.txt -DincludeScope=runtime

# the privileged process: it creates the directory owner-only, then binds inside it
java -cp "login-core/target/classes:$(cat login-core/target/classpath.txt)" \
  com.javafxlogin.core.authentication.ServiceProcess \
  /tmp/javafx-login/credentials.db --socket /tmp/javafx-login/authentication.sock

# the application, in another terminal
java -Djavafxlogin.socket=/tmp/javafx-login/authentication.sock \
  -cp "protected-feature/target/classes:$(cat protected-feature/target/classpath.txt)" \
  com.javafxlogin.feature.ProtectedFeatureLauncher
```

Let the service create that directory rather than creating it yourself: a socket bound by this
process takes its permissions from the `umask` at `bind()`, so it has to appear inside a directory
that is already restricted. On Linux in production none of this applies — the service is
socket-activated, systemd owns the socket and its mode is declarative, which is what the absence of
`--socket` selects. Packaging that is its own ticket, as is the Windows service.

**There is nobody to log in as yet.** A fresh store holds no `Account`, the first-run wizard that
creates the `Administrator` and the enrolment that creates `Operator`s are both still to be built,
and this project deliberately refuses to ship a default credential or a recovery key. Until those
land, what the pair does when run by hand is refuse every attempt — correctly. What an admitted
`Operator` sees is proven by the test suite instead.
