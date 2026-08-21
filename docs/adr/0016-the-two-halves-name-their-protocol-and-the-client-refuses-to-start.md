# The two halves name their protocol, and the client refuses to start without one

Issue #16 asks for two things that turn out to be the same thing: the application
must refuse to start when the `AuthenticationService` cannot be reached, and it
must say which of "not running", "incompatible version" and "socket not
accessible" happened. The first is easy to state and was already half-decided by
ADR-0002. The second is the one with a design in it, because two of the three are
not observable without adding something to the wire.

**The decision.**

- **The client refuses to start rather than degrading.** ADR-0002 makes the
  service the only party that can verify a password, so a login screen in front of
  a service that is not there is a gate that cannot gate anything. Nothing is
  drawn: no wizard, no login screen, no view of the host product's. One window
  appears, it names what happened and what to do about it, and it closes. This
  replaces the earlier behaviour, which showed the login screen and let the first
  attempt fail — honest as far as it went, and still an application that looked
  like it worked.
- **There is a protocol version, and both halves say theirs.** A client sends
  `AskWhichProtocolIsSpoken` and is answered `ProtocolSpoken`, carrying a number.
  It compares that number against its own and reports a mismatch as a mismatch.
  Without it the disagreement arrives as a `MalformedMessageException` — a parse
  failure, indistinguishable from a corrupt frame or a hostile peer, and useless
  to the person reading the screen.
- **That one exchange is frozen for the life of the product.** Every version of
  this product must read and write those two messages in exactly the shape they
  have now. A version that changed either of them would take away the only
  exchange two disagreeing builds can complete, and the disagreement would arrive
  as a parse failure again. The rule is written on `ProtocolVersion` and pinned by
  a round-trip test, which is the most a codec can do about a promise that spans
  releases.
- **The service answers it before the CredentialStore is touched.** It is the
  first case in `handle` and reads a constant. A client asks it precisely because
  it has no Session, no Account and no reason to believe the two halves agree, and
  a deployment whose store will not open must still be able to say what it speaks.
- **Silence is "not running", and this is a consequence of socket activation
  rather than a defect.** ADR-0002 chose systemd socket activation, so on Linux
  the socket is always present and connecting is what starts the service. There is
  therefore nothing for a client to observe that distinguishes "not running": the
  kernel accepts the connection into the backlog whether or not anything ever
  comes up behind it. What the client observes instead is that the handshake went
  unanswered, and that is exactly how a service which failed to start presents
  itself. The deadline is what turns that silence into a sentence.
- **The handshake has its own connection and its own clock.** Five seconds for the
  whole exchange, against a measured cold start of 179 ms including JVM boot. It
  does not go through `TransportClient`, which carries Sessions and must never be
  given up on: an Argon2id verification and a whole `Backup` cross that connection,
  and a timeout there would abandon work that was going fine.
- **The three reasons are told apart by exception type and never by message
  text.** The JDK reports a refused `AF_UNIX` connect as `BindException` where the
  kernel said `EACCES`, as `ConnectException` where it said `ECONNREFUSED`, and as
  a plain `SocketException` where the path does not exist. The messages behind
  those are the operating system's, in whatever language the machine is set to, so
  matching on them would be a diagnosis that stopped working in Spanish.

## What was considered and rejected

- **Inferring the version from which message first failed to parse.** No new
  messages, and it is the failure the ticket names: a client cannot tell a version
  it has never heard of from a peer sending nonsense, and both would reach a person
  as "something went wrong".
- **Sending the client's version and letting the service decide.** Rejected: it
  makes an older service responsible for being tolerant of a newer client it has
  never heard of, which is the one thing an older build cannot be. The party that
  has to act on the disagreement — the client, which is refusing to start — is the
  party that finds it.
- **Negotiating down to a common version.** There is one version and nothing to
  negotiate. Building the machinery now would be designing for a compatibility
  story that has not been decided; the decision worth making today is that the
  disagreement is *named*, which leaves every option open.
- **Probing the socket file's existence and mode with `Files` instead of
  connecting.** Rejected twice over: under socket activation the file is always
  there and says nothing about whether the service can start, and a permission
  worked out by reading a mode is a permission worked out by a different code path
  from the one that will actually be refused.
- **A retry button on the refusal window.** Under socket activation, connecting is
  what starts the service — the attempt that failed already was the retry. The
  other two remedies involve installing something or being added to a group,
  neither of which a button here could do.
- **Timing the handshake out on a second thread with a stopwatch.** Rejected in
  favour of a non-blocking channel and a selector: a thread waiting behind an
  answer that has already been given is a thread nobody is going to remember to
  stop.

## Consequences

- `Request` gains `AskWhichProtocolIsSpoken` and `Response` gains
  `ProtocolSpoken`; both are frozen, and `ProtocolVersion.CURRENT` is 1. It is
  raised in the same commit that changes what any other message means on the wire.
  Adding a message nobody sends yet does not change what an older build reads.
- `LoginGate` gains `reachability()`, answered by `ServiceHandshake` on a
  connection of its own and cached nowhere: a service found reachable a moment ago
  may have exited since, five minutes idle being all it takes.
- `GateFlow` asks two questions off the JavaFX application thread before anything
  is drawn — reachable, and then whether the wizard is needed — so the bounded wait
  is a stage that is still empty rather than a window that is frozen.
- The three reasons are wording like every other thing the service names: an enum
  in `login-core`, a key in `login-ui`, and a `WordingTest` that fails when a
  constant is added over there and worded nowhere.
- Windows keeps its own half of this. Its client must start the service and wait
  for the socket to appear, so "not running" is directly observable there and the
  timeout means something different. The reasons and the handshake are shared; only
  what produces them differs, which is the seam ADR-0003 already draws.
