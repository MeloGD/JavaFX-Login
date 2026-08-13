# Spike findings — on-demand activation of the AuthenticationService on Linux

**Date:** 2026-08-13
**Machine:** Ubuntu 26.04 LTS, systemd 259, Zulu JDK 21.0.12, Wayland/`ubuntu:GNOME`
**Status:** decided — **adopt Candidate A (systemd socket activation)**
**Nothing here shipped.** All units, rules and the test user were removed; the
machine is back to its prior state.

---

## 1. Recommendation

**Adopt Candidate A, systemd socket activation with `Accept=no`.** It was proven
end to end on this machine, it needs no privilege grant to the Operator at all,
and it preserves every property ADR-0002 and ADR-0003 require.

**Candidate B is not disqualified by polkit** — the scoped rule does exactly what
it was hoped to do, and that was verified. It loses on a structural defect
described in §4 that has nothing to do with authorisation.

---

## 2. The suspected blocker does not exist

The handoff assumed Java has no public API to adopt a systemd-inherited
descriptor. That is wrong, but the truth is narrower than "it just works".

`System.inheritedChannel()` is the supported API, and in this JDK it **does**
handle `AF_UNIX`. From the JDK's own source (`sun/nio/ch/InheritedChannel.java`
in `lib/src.zip`, lines 213-219): for a `SOCK_STREAM` socket of family `AF_UNIX`
that is **not connected**, it returns an `InheritedServerSocketChannelImpl`,
i.e. a real `java.nio.channels.ServerSocketChannel` bound to a
`UnixDomainSocketAddress`.

The real constraint is on the same page, line 176: `int fdVal = dup(0);`.
**`System.inheritedChannel()` only ever looks at file descriptor 0.** systemd's
`Accept=no` passes the listening socket as fd **3** (`LISTEN_FDS`), which is why
this looks impossible at first contact.

The bridge is one line of unit file: **`StandardInput=socket`**. Per
`systemd.exec(5)` on this machine, that option "is valid in socket-activated
services only, and requires the relevant socket unit file to have `Accept=yes`
set, **or to specify a single socket only**". A `.socket` with exactly one
`ListenStream=` and `Accept=no` satisfies the second clause, so systemd connects
the **listening** socket to fd 0, precisely where the JDK expects it.

**No reflection, no `sun.nio.ch`, no `--add-opens`, no `--add-exports`.** The
service code names only `java.lang.System` and `java.nio.channels`. The concrete
class of the returned object is an internal one, but the program never refers to
it as anything other than `ServerSocketChannel` — the same relationship every
`ServerSocketChannel.open()` call already has.

## 3. What was measured for Candidate A

Run as a root-owned system unit, with the unprivileged user connecting:

| Property required by | Result |
|---|---|
| Starts on demand (ADR-0002) | Client connect activated the service; first round trip **179 ms** including JVM start. The connection waits in the socket backlog while the JVM boots — no polling, no retry, no race. |
| Service is privileged, client is not | Client ran as `melo` (uid 1000), service replied `uid=0`. |
| Session bound to its connection, one process (ADR-0003) | Three consecutive Sessions served by **one** PID at 36 ms and 44 ms after the first. `Accept=yes` was never needed, so the per-connection-JVM problem never arises. |
| Stops after idle with no Sessions (ADR-0002) | Service exited by itself; socket unit stayed `active (listening)`. |
| Re-activation, repeatedly | 4 full start→serve→idle-exit→re-activate cycles across the user-level and system-level runs, new PID each time, no manual intervention. |

Idle timeout was set to 10 s for the spike; production is five minutes.

### Bonus: this supersedes the `umask` caveat in ADR-0003

Because systemd creates the socket, its ownership and mode are declarative:

```
srw-rw---- 1 root melo 0 Aug 13 13:04 /run/spike-authd-a.sock
```

`SocketUser=`, `SocketGroup=` and `SocketMode=` are applied by systemd at
creation. The socket never exists with wrong permissions, so it needs neither a
restricted parent directory nor a racy `chmod` after `bind()`. Candidate B, by
contrast, binds its own socket and got exactly the caveat ADR-0003 warns about —
measured as `rwxr-xr-x`, straight from `umask`.

## 4. Why Candidate B loses — and it is not polkit

### The polkit rule works, and that was verified properly

Measured with `pkcheck` (polkit's own query tool), against a live process of a
non-admin test user, with a **control phase** where the rule was absent:

| Query | Rule absent | Rule installed |
|---|---|---|
| `verb=start`, `unit=spike-authd-b.service` | `auth_admin_keep` (refused) | **`yes`** (silent, no prompt) |
| `verb=stop`, same unit | `auth_admin_keep` (refused) | `auth_admin_keep` (refused) |
| `verb=start`, a *different* unit | `auth_admin_keep` (refused) | `auth_admin_keep` (refused) |

So a polkit rule **can** grant a normal user a silent, unit-scoped, verb-scoped
`start` while genuinely refusing `stop` on the same unit. That question is
answered: yes.

### The structural defect

**With Candidate B the socket does not exist until the service is running.**
`RuntimeDirectory=` is removed when the unit stops, taking the socket with it.
This was observed directly: a client connect after an idle shutdown failed with
`java.net.SocketException` / ENOENT.

That forces the UI into a start-then-wait-then-connect dance: call
`systemctl start`, poll for the socket to appear, poll again until the service
has actually `bind()`ed and `listen()`ed, then connect — with a timeout and a
retry policy for each step, and a genuine race on every cold start. Candidate A
has none of this: the socket is always present, and connecting **is** the
trigger. ADR-0002's requirement that the app distinguish "not running" from
"socket not accessible" gets materially harder under B, because under B "not
running" and "socket absent" are the same observation.

Secondary costs of B: it grants the Operator a privilege it would not otherwise
hold (however tightly scoped), it depends on a polkit rule file surviving distro
upgrades and on polkit's JS rule engine remaining available, and it inherits the
`umask` socket-permission problem A eliminates.

### One caveat I must flag about B

The `pkcheck` results above are trustworthy. The **end-to-end `systemctl`
probes in my harness were not** — I could not get a clean measurement of a
sessionless user actually invoking `systemctl`, because running it from inside a
`pkexec`'d root script makes polkit attribute the caller to the invoking user's
active graphical session (visible in the journal: *"Operator of unix-session:2
… owned by unix-user:spiketest"*), which raises dialogs and can pick up retained
admin authorisations. In that contaminated setup a decoy unit started when it
should not have, and I could not explain it. **If the project ever revisits
Candidate B, that end-to-end check must be redone from a real login session of a
genuinely non-admin user.** It does not affect the recommendation, since B is
being rejected for §4's structural reason rather than on authorisation grounds.

## 5. The exact artifacts for the installer

`/etc/systemd/system/<name>.socket`:

```ini
[Unit]
Description=AF_UNIX socket for the AuthenticationService

[Socket]
ListenStream=/run/<name>.sock
SocketUser=root
SocketGroup=<dedicated group>
SocketMode=0660
Accept=no

[Install]
WantedBy=sockets.target
```

`/etc/systemd/system/<name>.service`:

```ini
[Unit]
Description=Privileged AuthenticationService
Requires=<name>.socket

[Service]
Type=simple
ExecStart=/path/to/java -cp /path/to/service AuthenticationService
StandardInput=socket
StandardOutput=journal
StandardError=journal
```

Notes for whoever writes the installer:

- `StandardInput=socket` is load-bearing. Without it `System.inheritedChannel()`
  returns `null`.
- Set `StandardOutput=`/`StandardError=` explicitly. Left at the default they
  inherit the socket, and anything the JVM prints would be written **into the
  client connection**. Service diagnostics must not go to stdout.
- `$LISTEN_FDS` / `$LISTEN_PID` are deliberately **not** set in this mode
  (documented, and confirmed: both were `null`). Do not have the service check
  them to detect activation; check `System.inheritedChannel()` instead.
- Only the `.socket` unit is enabled at boot. The `.service` must not be.
- `SocketGroup=` should be a dedicated group created by the installer, not a
  user's primary group (the spike used `melo` for convenience).
- Exactly one `ListenStream=` — a second one silently breaks the fd-0 mechanism.
- The polkit rule is **not needed** and should not be shipped.

## 6. Consequences for existing ADRs

**No ADR is contradicted.** Specifically:

- **ADR-0002** already names socket activation as the Linux half of the design.
  This spike confirms it is achievable, and removes the doubt that motivated the
  investigation. Its "five minutes without Sessions" shutdown is implementable
  exactly as written.
- **ADR-0003**'s `umask` consequence is **superseded on Linux**: with the socket
  created by systemd, the permissions are declarative and the restricted-parent-
  directory workaround is unnecessary. The bullet is not wrong, it is simply no
  longer binding for the Linux transport. Worth a small amendment when someone
  next touches that ADR — the caveat still applies to Windows, where the service
  binds its own socket.
- **ADR-0007 (no JPMS)** is unaffected: nothing here needs a module descriptor,
  and specifically no `--add-opens`/`--add-exports` are required.

## 7. Windows parity — analysis only, not tested

No Windows machine was available.

Adopting A means the two platforms **do not share one activation mechanism**:
Linux uses socket activation, Windows keeps the Manual-start service whose ACL
grants `SERVICE_START` but never `SERVICE_STOP` — which is Candidate B's shape.
This is not a regression against the design: ADR-0002's consequences already
describe exactly this asymmetric pair.

What they still share is everything that matters for the code: one `AF_UNIX`
transport, one framing, one Session-bound-to-connection model, one "starts on
demand, stops after five idle minutes" contract. Only the few lines that obtain
the listening `ServerSocketChannel` differ, and they are behind one seam.

**To verify on Windows before relying on this:** `System.inheritedChannel()` is
a Unix mechanism; on Windows it is expected to return `null`, so the Windows
service must bind its own socket and the UI must start the service and then wait
for the socket to appear — the very dance rejected in §4, but unavoidable there.
That start-then-wait path therefore still has to be designed and tested for
Windows, including its cold-start race and its "not running" vs "socket not
accessible" distinction.

## 8. Licensing

Nothing new is depended upon. The mechanism uses only the JDK's public API and
systemd unit files. No third-party library was added or needed, so there is no
licence question to clear for the Apache-2.0 product.
