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

## Installing it on Ubuntu

```bash
sudo apt install fakeroot        # jpackage shells out to it, and skips the .deb without it
./installer/linux/build-deb.sh   # jlink trims a runtime, jpackage packages it
sudo apt install ./target/package/dist/javafx-login_0.1.0_amd64.deb
```

That is the whole of it: log out and back in — a group membership does not reach a session that
already existed — and the product is in the applications menu. Nothing else is asked of the
machine, and nothing is running until the window is opened, which is what socket activation is
for. `docs/manual-checks/linux-packaging.md` is the checklist to hold an installation against.

The package installs an application and never a `Deployment`. The Accounts, the `SecretVault`,
the configuration and the `AuthenticationEvents` live in `/var/lib/javafx-login`, owner-only and
root-owned, and the package neither creates them — that is the first-run wizard's, once somebody
has logged in — nor removes them:

```bash
sudo apt remove javafx-login   # the application goes; the Deployment stays, and it says so
sudo apt purge  javafx-login   # the other word: it destroys the Deployment, and says what
```

An upgrade reasserts that directory's owner and mode rather than assuming they survived, and
brings the schema forward while somebody is still watching the installation: a store written by
a later build than the package stops the upgrade, naming both versions. ADR-0017 has the
reasoning, and the runtime the package carries is trimmed to the modules the product actually
reaches for — including the locale data the language selector needs, without which it offers
"Spanish" rather than "Español" and nothing fails.

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

## The language the interface is in

The product's interface is Spanish and English; the code and the documentation are English. Every
sentence a person reads lives in a `ResourceBundle` in `login-ui`:

```
login-ui/src/main/resources/com/javafxlogin/ui/login/
  languages.properties     the tags this build offers, in the order the selector lists them
  messages.properties      English, and what a machine with no bundle of its own is drawn in
  messages_es.properties   Spanish
```

Adding a language is a `messages_<tag>.properties` beside those and its tag in
`languages.properties`. No class names a language, and neither does the `AuthenticationService`:
it records whatever tag it is given and holds no list, so a language is never a change to the
privileged process.

Which language a screen is drawn in is decided twice. Before anybody has authenticated — the login
screen and the first-run wizard — it follows the machine's own locale, and the login screen carries
a selector for when that locale is not the language of whoever is at the keyboard. After an
admission it is the `LanguagePreference` of the `Account` admitted, which an `Administrator` sets
from the administration panel and which takes effect at that Account's next admission. See
ADR-0014.

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
`--socket` selects — the `.deb` above is where that comes from. The Windows service is still its own
ticket.

Start the service first. Before it draws anything the application asks the `AuthenticationService`
which protocol it speaks, and where it gets no answer it **refuses to start** rather than showing a
login screen it cannot log anybody in with — ADR-0002 makes the service the only party that can
verify a password. What it shows instead names which of three things happened, because the three
have different remedies: the service is **not running**, the two halves are from **incompatible
versions**, or the socket is there and this account **may not reach it**. The wait is bounded at
five seconds and happens off the thread that draws, so a machine with nothing listening produces a
sentence rather than a frozen window.

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

The `Administrator`'s half of it is the **administration panel**, which is reached from the same
login screen: tick *Entrar en la administración* and the attempt asks for the `Administrator` `Role`
rather than the `Operator` one. What is on it is the `Account`s of the deployment — the `Role`, the
coarse `PasswordStrength` band, the `LanguagePreference` and any `Lockout` — and what an
`Administrator` does about one of them: create an `Operator` and be handed the one-time secret,
initiate a reset, clear a `Lockout`, delete an `Operator` after being told what that costs, change
how long a `Session` may idle (or switch expiry off, which is what a kiosk is), copy the audit
record to a file, and write or restore a `Backup`. A `SecondFactor` control sits there visibly
disabled, because v1 implements none.

## Surviving the machine

A `Backup` is one file: every `Account` that holds a password, and the configuration, sealed under a
password the `Administrator` types at the moment of the export. Nothing about the machine that wrote
it goes into it, so it restores on a replacement — ADR-0006 chose that over binding it to a keyring,
because a backup that only restores where it was made is useless on the day that machine dies. What
is left protecting it is that password and Argon2id, which is why the screen says to keep it
somewhere other than the file.

What is **not** in it: the `SecretVault` and the `MachineKey`, the record of `AuthenticationEvent`s
and the key its chain is computed under, and any `Enrolment` somebody is halfway through — the
`Account` travels, the secret addressed to the machine that died does not, and a restore puts that
`Account` back waiting for a new one. Restoring also drops every wrapped copy of the `DataKey` the
`SecretVault` held, because those are keyed by names that now belong to nobody; the secrets
themselves stay. So a restored `Operator` logs in with the password they always had and is refused a
secret until an `Administrator` resets them and they enrol again. ADR-0015 says why, along with the
other things a restore refuses rather than guesses at.

Restoring replaces the store wholesale and never merges, which the panel says before it asks a
second time; a file that will not open, one from another version of the product and one that names
no `Administrator` are all refused before a single row is written, so the deployment is either the
`Backup`'s or exactly what it was. Both directions are `AuthenticationEvent`s, written to the record
this machine keeps — which is not in the `Backup`, so the lines either side of an import are the
deployment that used to be there.

Every one of those is refused in the privileged process unless the `Session` asking is an
`Administrator`'s, so drawing the window is not what grants it — ADR-0013 says what that costs and
why listing `Account`s does not undo ADR-0002. There is still no screen for changing your own
password: that request exists at the service and no window reaches it yet. This project ships no
default credential and issues no recovery key, and it never will.
