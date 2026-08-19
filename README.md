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
LoginGate.toService(socketPath).protect(stage, session -> myFeatureView());
```

The gate shows the login window, closes it once an `Operator` is admitted, and opens the view it
was handed on a stage of its own. It never learns what that view is. The `protected-feature`
module is a working example of exactly this, and nothing more — including the launcher class that
starting a JavaFX application from the classpath requires.

That stage belongs to the gate. Above the view it was handed it puts one control of its own, where
an `Operator` logs out, and it closes and returns the person to the login screen when the
`AuthenticationService` says the `Session` is over — after a period without activity, or because
the machine's clock moved. A host product writes none of that: the view it hands over is untouched,
and the stylesheet the gate's own windows use is scoped so that it cannot restyle it. See
ADR-0009 for how expiry is decided, and `CONTEXT.md` for what an `InactivityPeriod` is.

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

A fresh store holds no `Account`, so what the application shows first is the first-run wizard,
where the single `Administrator` is created. It is accepted only while no `Administrator` exists
**and** the account running the application administers the machine — see ADR-0008 — so on a
machine where you are not `root` and not in `sudo`, `wheel` or `admin`, the wizard will refuse you
and say so.

## What gets written down

Beside the `CredentialStore`, and owner-only like it, the service keeps
`authentication-events.csv`: every authentication attempt, `Lockout`, Account
change, configuration change and export, one line each, timestamped with an
offset. Each line is chained under an HMAC whose key lives next to it, so an
entry edited or removed in the middle breaks every entry after it. The file
rotates at a megabyte and keeps five, so it cannot fill a disk, and a record that
cannot be written never stops an authentication.

**Nothing in the application reads it back**, deliberately — an in-app viewer
would turn the record of what happened into one more thing to read out of the
application it audits. An `Administrator` exports it to a file and reads it with
their own tools; the export is checked against its own chain as it is copied, and
answers with how many entries there were and whether the chain held. ADR-0011 has
the reasoning, including what the chain is and is not worth. The request exists
at the service and no screen reaches it yet, for the same reason as clearing a
`Lockout`: that screen is the administration panel.

## The secrets behind the feature

A `ProtectedFeature` usually needs credentials of its own — for the systems it
connects to — and those have to survive on disk on a machine whose owner must not
read them. That is the `SecretVault`: its own file beside the `CredentialStore`,
owner-only like everything else the service writes, and reached through the same
`LoginGate` a host product already holds:

```java
switch (gate.secretNamed(session, "warehouse.database.password")) {
  case SecretGiven given -> connectWith(given.secret());
  case SecretWithheld withheld -> tellSomebody(withheld.reason());
}
```

**It does not unlock because authentication succeeded.** That boolean is exactly
what a patched binary would flip. It unlocks because the password the `Operator`
typed derives, through Argon2id with a salt and cost parameters of the Vault's
own, the key that unwraps the `DataKey` — and the stored authentication hash is
never reused as key material. A build with every check removed still cannot
produce those bytes. The `DataKey` is shared by every `Operator`, wrapped once per
`Operator` and once more under the `MachineKey`, which is what lets the service
provision somebody or rewrap after a reset with nobody present. Secrets are
decrypted one at a time at the moment of use, and the raw `DataKey` is not a type
anything outside the vault package can even name. ADR-0004 has the reasoning.

Completing an enrolment wraps the key; changing your own password rewraps it, so
rotating a password is not destructive; a reset takes it away with the password it
was under; and deleting an `Operator` destroys their copy, which is what makes
revocation real.

**The `Administrator` is refused every Vault operation, by the service and not by
the client — and this is least privilege, not a boundary.** Whoever holds the
`Administrator` password can create an `Operator`, enrol it, log in as it and read
every secret. Nothing here prevents that and nothing here claims to. What the
refusal buys is that the direct route leaves a line in the audit record and the
route that works leaves two more, on a chain that cannot be edited. ADR-0005 says
this at length, and neither this file nor the UI may say that secrets are
protected *from* the `Administrator`.

## How anybody comes to have a password

The `Administrator`'s own is chosen at the first-run wizard by whoever will use it. Every other
`Account` is created by the `Administrator` **without a password**: they choose the name and the
`Role`, and what they receive is a one-time enrolment secret of 128 bits to hand over. The person
who will use the `Account` types it at the login screen, chooses a password nobody else has ever
known, and logs in. Resetting a forgotten password is the same thing again — the old one stops
working the moment the reset is asked for, and its holder is told at their next login that it
happened and when. ADR-0012 has the reasoning, including what this does and does not protect.

**There is still no screen for the `Administrator`'s half of it.** Creating an `Account`,
initiating a reset, deleting an `Operator` and changing your own password exist at the service and
no window reaches them yet, for the same reason as
clearing a `Lockout` and exporting the record: that screen is the administration panel. Until it
lands, the pair started above shows the login screen and the enrolment screen behind it, and an
`Operator` to try them with has to be created through the service directly — which is what the test
suite does. This project ships no default credential and issues no recovery key, and it never will.
