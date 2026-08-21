# A privileged service owns the credential files

Password hashes must not be readable by unprivileged accounts — the same reason
Linux moved them out of `/etc/passwd` into `/etc/shadow` and Windows keeps the
SAM hive readable only by SYSTEM. But the graphical application runs as the
Operator, so it cannot read a file the Operator is denied. We therefore split
the system into an unprivileged UI process and a privileged AuthenticationService
that owns every credential file and performs verification on the UI's behalf.

This mirrors what both target platforms already do for their own logins: PAM
delegates to the setgid `unix_chkpwd`, and Windows delegates to LSASS over ALPC.

## Considered options

- **Setuid helper binary.** Rejected: a setuid JVM is exploitable through
  `JAVA_TOOL_OPTIONS` and friends, and Windows has no equivalent mechanism.
- **System group with a group-readable store.** Rejected: it forces every
  Operator to map onto an operating-system account, which defeats the point of
  the application having its own accounts.
- **World-readable hashes protected only by Argon2id cost.** Rejected: it leaks
  the account list and invites offline attack.

## Consequences

- Runtime privilege elevation disappears. The service already holds privileges,
  so administrative writes are authorised by verifying the Administrator's own
  password. There is no `pkexec` or UAC prompt during normal use, which also
  sidesteps the fact that a GUI cannot run as root under Wayland. Elevation is
  needed only at install time.
- Lockout state and the audit log become tamper-resistant, because the service
  owns those files too. Both must be flushed to disk on every write, since the
  service does not run continuously.
- Account names become part of what the CredentialStore keeps secret, not mere
  labels. Since an unprivileged attacker cannot read the account list, a
  predictable name donates an entry of it back for free. No Account is therefore
  pre-created, nothing is prefilled or suggested at first run, and a blocklist of
  predictable names — `admin`, `root`, `sa` and the like, matched
  case-insensitively after normalising separators and digit-for-letter
  substitutions — is refused for every Account. This satisfies ASVS 5.0 §6.3.2
  and goes past it: that requirement only forbids shipping such accounts, while
  here the concern is an installer creating one by hand. The Administrator and
  Operator Roles keep their names, being capability sets rather than credentials.
- The service starts on demand — socket activation under systemd, and a
  Manual-start Windows service whose ACL grants `SERVICE_START` (never
  `SERVICE_STOP`) to normal users — and stops after five minutes without
  Sessions. Consequently no rate-limiting state may live in memory.
- **Amended while building the Linux activation (issue #15): "without Sessions"
  is read as "without Sessions and without connected clients".** A connection
  with no Session behind it is a person at the login window who has not typed a
  password yet, and a process that exited under them would drop the connection
  their next attempt goes over — the client would then have to notice a dead
  channel and reconnect, which is the cold-start dance socket activation exists
  to avoid. Nothing is given up by counting it: a connection cannot outlive the
  client process holding it, because the kernel closes it when that process
  dies, and the five minutes begin there. ADR-0009 and ADR-0010 lean on this
  bullet for why nothing may be remembered in memory, and that reasoning is
  untouched — the widened reading makes the process live longer, never the
  state.
- Socket activation was proven on Ubuntu 26.04 with systemd 259 and JDK 21. The
  mechanism is not obvious and is easy to get silently wrong:
  `System.inheritedChannel()` inspects **only file descriptor 0**, while systemd
  passes the listening socket as fd 3, so the `.service` unit needs
  `StandardInput=socket` and the `.socket` unit exactly one `ListenStream=`.
  `StandardOutput=`/`StandardError=` must be set explicitly or JVM output is
  written into the client connection, and `$LISTEN_FDS` is deliberately not set
  in this mode. No reflection and no `--add-opens` are required. See
  `docs/spikes/linux-service-activation.md` for the measurements and the full set
  of installer traps.
- The application is no longer a single process. It remains self-contained: no
  network, no external service.
- If the service cannot be reached the application refuses to start,
  distinguishing "not running", "incompatible version" and "socket not
  accessible", because the three have different remedies.
