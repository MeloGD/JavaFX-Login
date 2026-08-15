# The first run is authorised by the peer's operating-system identity

There are no default credentials and there never will be, so the single
`Administrator` has to be created by somebody with nothing to prove it with. A
fresh install has no password to check, which leaves exactly one question the
privileged process can ask about whoever is asking: who does the operating system
say they are?

The `AuthenticationService` therefore refuses `Bootstrap` unless the connecting
peer runs as a `MachineAdministrator` — `root`, or a member of a group the
operating system treats as administrative. The peer's identity is read from the
socket with `SO_PEERCRED` when the connection is accepted, so nothing the client
sends takes part in it and a patched client cannot claim to be someone else. The
existing "no `Administrator` yet" check stays; the two guards are independent and
the peer is settled first, so a peer with no business here is told the same thing
on a fresh install as on one set up years ago.

This is what stops a normal user on a shared machine from claiming the
`Administrator` before the person who installed the product gets there. It is not
a defence against a `MachineAdministrator`, and cannot be: on both target
platforms someone who administers the machine can read or replace the store
anyway, which ADR-0001 already accepts.

## Considered options

- **Nothing: whoever runs the wizard first wins.** Rejected: on a multi-user
  machine the account list is unreadable (ADR-0002) but the wizard is not, so the
  first unprivileged user to log in after installation could take the role and
  lock the owner out with a password only they know.
- **An installation secret written by the installer, typed into the wizard.**
  Rejected: it is a shipped credential in all but name, it has to be conveyed to
  the person somehow, and losing it has no remedy that is not a backdoor.
- **Requiring the wizard to run as `root`.** Rejected: ADR-0002 already records
  that a GUI cannot run as root under Wayland, and elevation is meant to be
  needed only at install time. Membership of an administrative group is the
  identity the person who installed the product actually has while sitting at
  their desktop session.
- **Asking the operating system for the peer's full group list.** Rejected for
  now: on Linux `SO_PEERCRED` reports the primary group only, and resolving the
  rest goes through NSS — in practice by running `id -nG` from a process running
  as root. Spawning a subprocess there buys robustness at a cost we would rather
  not pay for one check.

## Consequences

- Group membership is read from the machine's own local group database
  (`/etc/group`) plus the primary group the kernel reported. A deployment whose
  administrators come from a directory service will not be found there, and the
  person installing has to be `root` or a local administrator. This is the safe
  direction to be wrong in: the failure is a wizard refused, not a wizard handed
  to a stranger.
- The administrative group names are fixed in the code — `root`, `sudo`, `wheel`,
  `admin` — rather than configured. A deployment that could name its own
  administrative group could name one it controls, and this is the check that
  decides who claims the `Administrator`.
- Every failure to read the group database is an exclusion, `root` apart. A
  platform that will not name its peer at all — Windows has no `SO_PEERCRED` —
  gets no bootstrap, which is consistent with its listening channel being
  unbuilt. When the Windows service is written, the answer there comes from the
  peer's token rather than from a group database, and it sits behind the same
  seam.
- Whether the wizard is *needed* stays readable by anyone who can reach the
  socket, because a client has to choose which window to open before it knows
  anything else, and a fresh install reveals the answer the moment it draws one.
  Being told the wizard is needed is not being allowed to run it.
- The connection now reaches the request handler carrying the peer's identity.
  That was already the shape — the handle arrived with the request so a Session
  could be bound to it — and this is its first reader.
