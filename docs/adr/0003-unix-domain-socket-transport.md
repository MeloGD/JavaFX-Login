# Unix domain sockets carry the IPC, framing length-prefixed JSON

The UI process and the AuthenticationService need a channel whose endpoint has an
owner and permissions, and whose peer cannot be an arbitrary process on the
machine. A Unix domain socket is addressed by a filesystem path rather than an
`ip:port`, is unreachable from other machines, and inherits filesystem access
control. Java has supported it natively since 16, and Windows 10 build 17063 and
later support `AF_UNIX`, so one implementation covers both target platforms.

Messages are JSON with a four-byte length prefix and a hard 1 MiB cap.

## Considered options

- **TCP on loopback.** Rejected: any process of any user can connect and there is
  no peer identity.
- **Java RMI.** Rejected: it runs over TCP and its deserialisation is a known
  remote-code-execution vector in a process running as root.
- **D-Bus.** Rejected: idiomatic on Linux, absent on Windows.
- **Shared files or shared memory.** Rejected: no peer identity, and racy.
- **Hand-rolled binary framing.** Rejected: it buys obscurity rather than
  security — a third party cannot observe an `AF_UNIX` channel without tracing
  one of the endpoints, which requires root or the same identity — while costing
  debuggability and placing a hand-written parser inside a root process.

## Consequences

- The password crosses the channel in the clear, as it does with PAM and LSASS.
  A challenge-response scheme was rejected because verifying against Argon2id
  requires the original password; deriving client-side would turn the derived
  value into the password.
- On Windows the service binds its own socket, which therefore inherits `umask`
  permissions at `bind()` and must be created inside an already-restricted
  directory rather than chmod'ed afterwards. **On Linux this does not apply**:
  systemd creates the socket under socket activation, so `SocketUser=`,
  `SocketGroup=` and `SocketMode=` make its ownership and mode declarative and it
  never exists with the wrong permissions.
- The two platforms share the transport, the framing and the Session model, but
  **not the activation trigger**. Linux is socket-activated, so connecting is what
  starts the service and the socket is always present. Windows keeps a
  Manual-start service and must start it and then wait for the socket to appear.
  Only the few lines that obtain the listening `ServerSocketChannel` differ, and
  they sit behind one seam.
- A Session is bound to its connection. When the client dies the kernel closes
  the socket and the service ends the Session immediately — no heartbeats, and no
  Operator locked out by a crashed client.
