# Manual check — Linux packaging

What a `.deb` does to a machine cannot be exercised by a suite: dpkg is not in it, and what is
being checked is what a machine is left like rather than what any code computes. What *is*
tested automatically is named under each step, so that this checklist covers the machine and
nothing else. `DebianPackageTest` holds the maintainer scripts to what is written below.

Most of what is below is now run by `installer/linux/verify-on-a-machine.sh`, which is worth
starting with: it takes a throwaway machine from a build to a purge and says, per step, what it
expected and what it found. What it does not cover is marked under the section it belongs to, and
this document is still the whole list — a script only checks what somebody already knew to check,
and neither defect the first two hand-runs of this found would have been caught by one.

Run it on a machine nothing has been installed on — a fresh Ubuntu virtual machine is the
honest version of this — from an ordinary account that can `sudo`. Half of what is being
checked is that a person who installs this product can then log into it without being told
one more thing to do.

A booted machine is the only thing checked here, and that is the whole of how this product
is delivered: somebody installs the `.deb` by hand on the machine they are sitting at. It is
not baked into images and nothing automated deploys it, so there is no chroot step below and
there is no need for one. `install.sh` does have a branch for a `systemctl` that cannot run —
it puts the group, the directory and the units in place and says what it could not enable —
but that is defence against a machine in a state nobody meant to be in, not a promise that
installing into an image works. A build that ever needs that promise needs an ADR first.

Work top to bottom: every step depends on the state the one before it left.

---

## 0. Building the package

On the build machine, from the repository root:

```
sudo apt install fakeroot           # jpackage shells out to it and skips the DEB bundler without it
./installer/linux/build-deb.sh      # runs the suite; --skip-tests where it has just been run
ls target/package/dist/*.deb
```

- [ ] The build ends with a `.deb` of about 55 MB, most of which is the trimmed runtime.
- [ ] It said nothing about a skipped bundler. jpackage reports a missing `fakeroot` by
      *skipping* the DEB bundler with a message, and then exits successfully having built
      nothing.

_Covered automatically:_ that the launcher the `.service` unit starts runs on the runtime that
was just linked — `build-deb.sh` asks it, in its upgrade mode, before it packages anything.

_Covered by `verify-on-a-machine.sh`:_ both boxes, and that every entry in the `.deb` is
`root/root`. It builds with `--skip-tests`, because what the suite says is a build-machine
question and that script is only ever about what a machine is left like — and it builds with the
JDK named in `maven.compiler.release`, installed by version, rather than with whatever `javac` the
machine has. On Ubuntu 26.04 `default-jdk` is 25, and installing `maven` pulls one in whether
anything asked for it or not; a `.deb` linked by a `jlink` nobody here builds with is not the
`.deb` this repository ships, and the difference would land in the trimmed runtime, which is the
one thing that script cannot see.

## 1. A first installation

```
sudo apt install ./javafx-login_0.1.0_amd64.deb
```

- [ ] It ends without an error, and without asking anything.
- [ ] `getent group javafx-login` names the group, and your own account is in it.
- [ ] `ls -ld /var/lib/javafx-login` → `drwx------ root root`.
- [ ] `systemctl is-enabled javafx-login-authd.socket` → `enabled`, and
      `systemctl is-enabled javafx-login-authd.service` → `static`.
- [ ] The installation said there is no CredentialStore yet — before it said anything about
      the machine. A first installation has nothing to migrate, and must not be what creates
      a deployment.
- [ ] `ls /var/lib/javafx-login` is empty: no store, no SecretVault, no event log.

_Covered automatically:_ that the upgrade mode creates nothing where there is nothing —
`UpgradeBringsTheFilesForwardTest`.

_Covered by `verify-on-a-machine.sh`:_ every box, and two more — that both units are installed,
and that exactly one `.desktop` entry is registered and it is not the AuthenticationService's. It
performs the installation with `SUDO_USER` set, which is how sudo says who an installation was
for, so the group membership is a real one rather than the one a root shell would leave. **The
half of the first box that says "without asking anything" stays here**: that script sets
`DEBIAN_FRONTEND=noninteractive`, which is what makes an installation that wanted to ask fail
rather than ask, so a person running this by hand is the only one who ever sees the question.

## 2. The one manual step there is

```
id -nG          # javafx-login is not here yet in this shell
```

- [ ] Log out and back in, and `id -nG` now names `javafx-login`.

A group membership does not apply to a session that already existed. Nothing can be done
about that from a package, and it is the one thing the installation cannot finish on its own.

If your account is not in the group at all, the installation was run from somewhere that does
not say who it was for — a root shell rather than `sudo`, or a package manager that discards
the environment:

- [ ] The installation said so, in as many words, and named the command to run:
      `sudo /opt/javafx-login/lib/systemd/install.sh <account>`.

Run it, log out and back in, and carry on. An installation that admitted nobody and said
nothing would end at a login window reporting that the AuthenticationService is not running.

## 3. Logging in, with nothing else done to the machine

Start **javafx-login** from the applications menu — jpackage names the entry after the
package, so that is the word to look for.

- [ ] The menu offers exactly one entry. There is no entry for the AuthenticationService: it
      is not something a person starts.
- [ ] The first-run wizard appears — a fresh deployment holds no Administrator — and creates
      the Administrator without complaint. It refuses if the account is not a
      MachineAdministrator, which is ADR-0008 and not a packaging fault.
- [ ] Log in as that Administrator. The whole of steps 1 to 3 was: install, log out, log in.
- [ ] The language selector offers **Español**, spelled that way. A trimmed runtime that lost
      its locale data names it "Spanish", and nothing else goes wrong.

_Covered automatically:_ that the packaged runtime is linked with locale data for every
language the selector offers — `TheTrimmedRuntimeCarriesEveryOfferedLanguageTest`.

## 4. The service is where socket activation left it

```
systemctl is-active javafx-login-authd.service    # → active, while the window is open
ls -l /run/javafx-login-authd.sock                # → srw-rw---- root javafx-login
```

- [ ] The service started only when the application connected.
- [ ] The socket is the one the application connects to. A packaged installation is where the
      two halves would disagree about that path and nobody would find out: the client would
      report the service as not running, on a machine where it is installed and well.

_Covered automatically:_ that the application's installed path is the unit's `ListenStream=` —
`TheInstalledSocketIsTheOneSystemdListensOnTest`. The rest of the activation behaviour has a
checklist of its own: `linux-service-activation.md`.

## 5. Reinstalling reasserts what an upgrade could have loosened

Close the application. Then loosen the deployment by hand and put it back with a reinstall:

```
sudo chmod 0755 /var/lib/javafx-login
sudo apt install --reinstall ./javafx-login_0.1.0_amd64.deb
ls -ld /var/lib/javafx-login                      # → drwx------ root root again
systemctl is-active javafx-login-authd.service    # → inactive, and never failed
```

- [ ] The mode is `0700` and the owner is `root`, without anybody having repaired it.
- [ ] The installation reported the CredentialStore's schema version, rather than saying there
      was nothing there.
- [ ] The service is `inactive`, **not `failed`**. The prerm stops it, and a JVM that is asked
      to stop ends at 143 — so without `SuccessExitStatus=` in the unit, every upgrade and
      every removal leaves the unit failed for as long as the machine is up, and
      `systemctl --failed` names a machine that is installed and well. Socket activation goes
      on working either way, which is what makes it worth checking rather than noticing.
- [ ] Log in again as the same Administrator with the same password. The Accounts, the
      configuration and the SecretVault came through the reinstall untouched.

An upgrade that quietly loosened that directory would take away the only real security
property this product has, and everything would go on working.

_Covered by `verify-on-a-machine.sh`:_ every box, and that the files under
`/var/lib/javafx-login` come through **byte-identical**. It leaves the service running when the
reinstall starts, on purpose: the `prerm` is what stops it, and stopping it is the only thing that
exercises `SuccessExitStatus=143`. The last box it answers by logging in over the socket rather
than through the window — whether a person gets a window stays here.

## 6. A store from a later build stops the installation

```
sudo apt install sqlite3
sudo sqlite3 /var/lib/javafx-login/credentials.db 'PRAGMA user_version = 99'
sudo apt install --reinstall ./javafx-login_0.1.0_amd64.deb    # → fails
```

- [ ] The installation **fails**, and the message names both numbers: the version found and
      the version this build understands.
- [ ] Nothing is listening: `ss -lx | grep javafx` finds nothing, `/run/javafx-login-authd.sock`
      is gone with the unit that made it, and starting the application reports the service as
      not running rather than opening a login window. The refusal happens before the machine
      is wired, on purpose — a refused upgrade must leave nothing for anybody to connect to
      rather than a service that dies on activation.
- [ ] Put it back, and finish the configuration dpkg left half-done:

      ```
      sudo sqlite3 /var/lib/javafx-login/credentials.db 'PRAGMA user_version = <the number it named>'
      sudo dpkg --configure javafx-login
      ```

      `apt install --reinstall` is **not** the way back: a package whose postinst failed is
      half-configured, and apt refuses it with `Internal Error, No file name for
      javafx-login:amd64` rather than running the postinst again. dpkg is what re-runs it.
- [ ] It succeeds, `dpkg -l javafx-login` reads `ii`, and the socket is listening once more.

This is the downgrade path, and it must fail here rather than at the next login: under socket
activation a service that refuses to start is indistinguishable from one nobody has connected
to yet.

_Covered automatically:_ the refusal and the two numbers in it —
`UpgradeBringsTheFilesForwardTest`, `CredentialStoreSchemaTest`.

_Covered by `verify-on-a-machine.sh`:_ every box, including that `apt install --reinstall` is not
the way back — it asserts that the reinstall fails and that the machine is still not `ii` after
it, rather than trusting the sentence above. Of the second box it answers the machine's half:
nothing is listening and the node is gone. **What a person starting the application is told stays
here**, and it is the half that matters to whoever hits this.

## 7. Removing keeps the deployment

```
sudo apt remove javafx-login
```

- [ ] It says `/var/lib/javafx-login` has been kept, and names what is in it.
- [ ] `/opt/javafx-login` is gone; `/var/lib/javafx-login` is still there, still `0700`.
- [ ] `systemctl is-enabled javafx-login-authd.socket` → the unit is gone, nothing is
      listening, and `/run/javafx-login-authd.sock` is gone too. `RemoveOnStop=` in the socket
      unit is what takes the node with it; systemd's default would leave a root-owned socket
      in `/run` naming the product's group on a machine the product has left.
- [ ] The menu entry is gone.

Then install it again and log in as the same Administrator:

- [ ] The Accounts, their passwords, the Lockout state and the AuthenticationEvents are all
      where they were. A reinstall is not a way to lose a deployment.

_Covered by `verify-on-a-machine.sh`:_ every box. The last one it answers twice — the deployment
is byte-identical after the second installation, and the same Administrator logs in again over the
socket with the same password.

## 8. Purging destroys it, and says so

```
sudo apt purge javafx-login
```

- [ ] Before it is gone, the message names what is being destroyed: every Account and its
      password, the SecretVault and every secret in it, the configuration, and the record of
      every authentication ever attempted — and that a Backup taken earlier is the only copy
      that survives.
- [ ] `/var/lib/javafx-login` is gone.
- [ ] `getent group javafx-login` names nothing, and `ls /run/javafx-login-authd.sock` finds
      nothing. A socket node left behind here would name a gid the `groupdel` above has just
      freed, and would go on meaning it to whoever is handed that gid next.

_Covered automatically:_ that only the purge destroys anything, and that it says what —
`DebianPackageTest`.

_Covered by `verify-on-a-machine.sh`:_ every box, and that `dpkg-query` no longer knows the
package. It is the last step that script runs, and the state it leaves the machine in.

## 9. The attribution the licences require

```
cat /opt/javafx-login/share/doc/copyright                  # before the purge above
cat /opt/javafx-login/lib/doc/THIRD-PARTY-NOTICES.md
```

- [ ] Both are there, and both name OpenJDK and OpenJFX under the GPL with the Classpath
      Exception.

_Covered automatically:_ that the notices name them and that the package ships the file —
`DebianPackageTest`.

---

## Verifying the trimmed runtime against the whole suite

Not part of an installation, and the strongest check there is that the module list in
`build-deb.sh` covers what this product actually reaches for. Run the suite **on the runtime
that ships** rather than on the JDK it was linked from:

```
jlink --add-modules "$(sed -n "s/^readonly RUNTIME_MODULES='\(.*\)'/\1/p" installer/linux/build-deb.sh),java.management" \
      --include-locales en,es --output /tmp/shipped-runtime
mvn test -Djvm=/tmp/shipped-runtime/bin/java
```

- [ ] The whole suite passes, including the JavaFX windows.

`java.management` is added for the test harness and not for the product: Surefire's forked
booter asks for it, and a package that shipped it would be shipping a module only the suite
ever needed. That difference is the only one between the image linked here and the image in
the `.deb`; this is how the missing `jdk.localedata` was found.
