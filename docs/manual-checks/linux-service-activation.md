# Manual check — Linux service activation

None of this is unit-testable: systemd is not in the suite, and what is being checked is
what systemd does with a process rather than what the process computes. What *is* tested
automatically is named under each step, so that this checklist covers the machine and
nothing else.

Run it on a machine the product has been installed on with `installer/linux/install.sh`,
from an ordinary account that is a member of the `javafx-login` group — **not** from a root
shell, and not with `sudo` in front of the client. Half of what is being checked here is
that an unprivileged account needs no privilege at all.

Work top to bottom: several steps depend on the state the one before it left.

---

## 0. The product is where the unit says it is

```
ls -l /opt/javafx-login/runtime/bin/java   # → executable
ls    /opt/javafx-login/lib/*.jar          # → at least one
ls -ld /var/lib/javafx-login               # → drwx------ root root
```

- [ ] The launcher named by `ExecStart=` exists and is executable.
- [ ] The state directory is root-owned and `0700`: nothing unprivileged may read the
      CredentialStore, the SecretVault, the Lockout records or the AuthenticationEvents.

`install.sh` refuses to enable anything when the first of these is missing, because the
failure it would otherwise cause is the quiet one: a socket that listens, and an activation
that dies on `ExecStart` the first time somebody tries to log in.

## 1. Before anything has connected

```
systemctl is-enabled javafx-login-authd.socket   # → enabled
systemctl is-active   javafx-login-authd.socket  # → active
systemctl is-enabled javafx-login-authd.service  # → static  (never "enabled")
systemctl is-active   javafx-login-authd.service # → inactive
```

- [ ] The socket unit is enabled and listening.
- [ ] The service unit is **not** enabled, and is **not** running.

A service that is already running here has been enabled by hand, and the whole point of
the design is gone: there is a privileged JVM up on a machine nobody has logged in to.

_Covered automatically:_ that the shipped `.service` has no `[Install]` section at all —
`SystemdUnitFilesTest.onlyTheSocketIsEnabledAtBoot`.

## 2. The socket's ownership and mode are what was declared

```
ls -l /run/javafx-login-authd.sock   # → srw-rw---- 1 root javafx-login
getent group javafx-login            # → javafx-login:x:<gid>:<the admitted accounts>
```

- [ ] Owner `root`, group `javafx-login`, mode `0660`, and a socket (`s`) rather than a file.
- [ ] The group is the dedicated one, and is not any person's primary group
      (`id -gn <account>` must not print `javafx-login`).

The socket was created by systemd before anything ran, so it has never existed with any
other permissions — there is no window here to lose a race in.

_Covered automatically:_ that the unit declares `SocketUser=`, `SocketGroup=` and
`SocketMode=` — `SystemdUnitFilesTest.theSocketsOwnershipAndModeAreDeclaredRatherThanLeftToUmask`.

## 3. Connecting starts the service, and the connection waits

Start the application (or any client that connects to the socket) and time the first
answer.

- [ ] The client's first request is answered without it having started anything, polled for
      anything, or retried anything.
- [ ] `systemctl is-active javafx-login-authd.service` now prints `active`.
- [ ] `journalctl -u javafx-login-authd.service -n 20` shows the service started **after**
      the client connected.

The connection waits in the socket's backlog while the JVM boots. The spike measured 179 ms
for the first round trip on Ubuntu 26.04; anything of that order is right, and a *failure*
to connect is the thing to stop on.

## 4. One process serves several Sessions in turn

With the client still connected, log in, log out and log in again — three Sessions.

```
systemctl show -p MainPID --value javafx-login-authd.service
```

- [ ] The PID is the same before and after all three, so `Accept=no` is doing what it says:
      one process, not one JVM per connection.

## 5. Diagnostics reach the journal and never the client

```
journalctl -u javafx-login-authd.service --since '10 min ago'
```

- [ ] Whatever the service has said is here.
- [ ] The client never displayed, logged or choked on a line of service diagnostics, and no
      request was answered with anything that is not a response of the protocol.

If a JVM warning ever reached a client, `StandardOutput=` has been left at its default and
is inheriting the socket. That is the trap this step exists for.

_Covered automatically:_ that the unit sets both streams to the journal —
`SystemdUnitFilesTest.theServicesDiagnosticsGoToTheJournalAndNotIntoAClientConnection`.

## 6. It stops by itself once nobody is using it

Close every client — the application, and any `nc`/`socat` left open — and note the time.

```
watch -n 30 systemctl is-active javafx-login-authd.service
```

- [ ] The service goes `inactive` about five minutes after the last client closed, without
      anybody stopping it.
- [ ] It does **not** stop while a client is still connected, even an idle one sitting at
      the login window.
- [ ] `systemctl is-active javafx-login-authd.socket` still prints `active`, and
      `/run/javafx-login-authd.sock` is still there.
- [ ] The journal records an ordinary exit, not a failure: `Deactivated successfully`.

_Covered automatically:_ the countdown itself and what counts as being in use —
`IdleShutdownTest`, and `ServiceStopsWhenNobodyIsUsingItTest` over a real socket.

## 7. It comes back, repeatedly

- [ ] Connect again: the service starts, the PID is a new one, and the client is answered.
- [ ] Let it go idle and do it a third time. Four cycles were run during the spike.

An activation that works once and not twice usually means the socket was removed by the
service on the way out. Nothing that adopted an inherited channel may delete the socket
file: it belongs to systemd and is what the next activation arrives on.

## 8. Nothing was remembered across the idle exit

Get an Account locked out (wrong password, as many times as the LockoutPolicy allows),
then let the service go idle and connect again.

- [ ] The Account is **still** locked out after the service has stopped and started again.
      Waiting out a privileged process must not be a way to clear a Lockout, and an
      Enrolment that was issued must still be waiting.
- [ ] No Session survived: the client is told its Session ended rather than being answered
      as though it were still live.

_Covered automatically:_ that the state is on disk rather than in memory —
`LockoutTest`, `EnrolmentTest`, `FileAuthenticationEventLogTest`.

---

## If a step fails

The four ways this fails silently, all of them in the unit files
(`docs/spikes/linux-service-activation.md` §5):

1. **Nothing is inherited, and the service refuses to start.** `StandardInput=socket` is
   missing, or the `.socket` declares more than one `ListenStream=`. `System.inheritedChannel()`
   looks only at file descriptor 0, and either mistake stops systemd putting the listening
   socket there.
2. **The client receives rubbish.** `StandardOutput=`/`StandardError=` were left at their
   default and are inheriting the socket.
3. **The service is checked for `$LISTEN_FDS`.** It is deliberately not set in this mode.
   Activation is detected by asking for the inherited channel and never by reading that
   variable.
4. **The service is enabled at boot.** Only the socket is.
