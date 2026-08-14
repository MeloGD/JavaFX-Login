# Code review — the wire (issue #2, Seam 2)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `wire-transport` (pushed to `origin`) |
| Commits | `0ed4d22` (implementation), `5d23a42` (review fixes) |
| Base / fixed point | `5879e5c`, the tip of `worktree-design-docs` |
| Diff to review | `git diff 5879e5c...HEAD` |
| Package | `com.javafxlogin.core.ipc` in `login-core` |
| Build | `mvn -o test` → 44 tests, 0 failures, 1 skipped by an OS guard |

**Branch base is not `main`.** `wire-transport` was cut from
`worktree-design-docs`, not from `main` or `dev-login`, because the Maven
skeleton, `CONTEXT.md` and the ADRs exist only there. `main` and `dev-login` are
still at `49535c6` with no Java in them. Any review that diffs against `main`
will see the design docs as part of this change; they are not.

## 2. What the ticket asked for

Issue #2, "The wire: length-prefixed framing and AF_UNIX transport" — the channel
the unprivileged client and the privileged `AuthenticationService` talk over, and
nothing else. Parent spec is issue #1; the binding decision is ADR-0003
(`docs/adr/0003-unix-domain-socket-transport.md`) and the measurements in
`docs/spikes/linux-service-activation.md`.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Round trip over `AF_UNIX` in a temp dir | met | `TransportTest.completesARequestResponseRoundTripOverAUnixDomainSocket` |
| Over-cap rejected **without its body being read**, connection closed | met | `TransportTest.refusesAnOversizedDeclarationWithoutWaitingForTheBodyBehindIt` (declares over-cap, then sends real body bytes) + `…WithoutReadingItsBodyAndClosesTheConnection` + `FrameCodecTest.rejectsADeclaredLengthAboveTheCapAsSoonAsThePrefixArrives` |
| Frame split across several reads | met | `FrameCodecTest.reassemblesAFrameSplitAcrossSeveralReads` (byte at a time), `TransportTest.reassemblesARequestSplitAcrossSeveralWrites` |
| Several frames in one read | met | `FrameCodecTest.separatesSeveralFramesArrivingInASingleRead`, `…WhenAReadStraddlesTheBoundary`, `TransportTest.separatesSeveralRequestsArrivingInASingleWrite` |
| Malformed or truncated closes the connection | met | `TransportTest.closesTheConnectionOnAMalformedFrame`, `…OnATruncatedFrameRatherThanGuessingAtIt` |
| Concurrent connections isolated | met | `TransportTest.servesConcurrentConnectionsWithoutEitherSeeingTheOthersTraffic`, `…UnderLoad` (8 clients × 20 round trips) |
| Close produces the Session-ending signal, fakeable by Seam 1 | met | `ConnectionHandle` (2 methods); `TransportTest.signalsTheHandleWhenTheClientDisappears…`, `…RegisteredAfterTheConnectionAlreadyClosed`, `…WhenTheServerItselfStops` |
| Acquisition behind its own component; Linux adopts, Windows unimplemented | met | `ListeningChannelSource`; `ListeningChannelSourceTest`, Windows case `@EnabledOnOs(OS.WINDOWS)` so it **skips on Linux rather than passing** |
| Headless, no display, no privileges | met | verified by running the suite as uid 1000 with `DISPLAY`/`WAYLAND_DISPLAY`/`XAUTHORITY` unset |

## 3. Design decisions a reviewer should judge, not rediscover

- **Payloads are opaque bytes.** The transport promises whole, capped, ordered
  frames; it does not parse JSON. ADR-0003 says "Messages are JSON", so *this
  clause is deliberately deferred* to the ticket that introduces the message
  types, along with Jackson. Consequence today: "malformed" means
  length-malformed only, and arbitrary non-JSON reaches the handler. **This is
  the largest open question in the change** — see §5.
- **Thread per connection, on virtual threads.** Keeps a Session and its
  connection in step and keeps two clients' traffic apart by construction.
  Requests on one connection are serialised; `RequestHandler` implementations
  must be thread-safe across connections.
- **`ConnectionHandle` is the Seam 1 / Seam 2 join.** The handler receives the
  request together with the connection it arrived on, so Seam 1 can hand the
  service a handle it closes by hand, while Seam 2 proves a real socket closing
  fires the same signal. Deliberately two methods, so faking it is trivial.
- **`ListeningChannelSource.release()`** exists so the source that *created* a
  socket file removes it, while the inherited (systemd) source deliberately does
  nothing — the socket unit stays listening after the service exits, and deleting
  the file would break the next activation.
- **The inherited path refuses rather than falling back.** No channel, an unbound
  channel, a non-listening channel, or a TCP channel all raise
  `ListeningChannelUnavailableException`. Quietly binding its own socket would
  produce one whose mode came from `umask` — exactly what the declarative systemd
  socket exists to prevent.

## 4. Two-axis review: what was found and what was done

Reviewed by two independent agents (standards axis, spec axis) against
`5879e5c...HEAD` at commit `0ed4d22`.

### Acted on, in `5d23a42`

1. **The cap's central promise was under-proven.** The over-cap test sent a
   prefix and *no body*, making "the body was not read" vacuous, and the
   no-allocation test declared a *legal* length. Added a test that declares
   over-cap and puts real bytes behind it. Verified it bites: with the cap
   broken, the server hangs waiting for a body that never comes.
2. **Overflow in `FrameDecoder.makeRoomFor`.** It computed `copyOfRange`'s end
   index as `start + capacity`, which can wrap to a negative index near 2 GiB —
   in the one method whose comment claimed overflow safety. Now an explicit
   `new byte[capacity]` + `System.arraycopy`. Unreachable while the 1 MiB cap
   holds; fixed because the comment claimed otherwise. Growth with a consumed
   prefix is now covered by a test.
3. **`hasPartialFrame()` had no caller in `src/main`** and a truncated frame is
   indistinguishable from a clean goodbye to the peer, so no observable
   behaviour rode on it. Deleted; its tests now assert that nothing partial is
   ever delivered. `FrameTooLargeException.declaredLength()` went with it, and
   the exception now reports the cap **in force** rather than always the 1 MiB
   constant.
4. **A false claim in a class comment.** "its body is neither read nor buffered"
   overstated it: whatever shared the prefix's read is held, bounded by the read
   buffer, then discarded with the connection. The comment now says that.

### Deliberately not acted on — open for the next reviewer

| Finding | Axis | Why it was left |
|---|---|---|
| **JSON framing unimplemented** (ADR-0003 "Messages are JSON") | Spec | Layering call, not an oversight. Needs an explicit accept-or-reject; if rejected, ADR-0003 or the next ticket should record where JSON validation lands. |
| **`BoundListeningChannelSource` ships in `src/main`** though only Seam 2 tests use it, and it is effectively the Windows mechanism where the criterion says "left unimplemented" | Spec (scope creep) | Test-only production code is a real smell. Options: move to test scope, or keep and document it as the Windows seed. Not decided. |
| **Client-side cap + close-on-unreadable-answer; 50 ms accept-retry pause** | Spec (scope creep) | Added as hardening during implementation, before the review flagged them as unasked-for. Both are defensible (the ADR-0003 rule is symmetric; an EMFILE storm would otherwise spin the accept loop) but neither was requested. Reviewer's call. |
| **`CONTEXT.md` lists `server` under AuthenticationService's `_Avoid_`**, and `TransportServer` plus test prose use it | Standards | Judgement call. `TransportServer` names a transport role, not the domain component, and its Javadoc says "the service". But this is the first Java in the repo and sets precedent. |
| **Duplicated write-until-drained and read-and-drain loops** (4× and 3× across `TransportServer`, `TransportClient`, `TransportTest`), duplicated `READ_BUFFER_BYTES`, `closeQuietly`, and `lengthPrefix` | Standards | Real duplication. Extracting it across a main/test boundary needs a decision about where a shared helper would live. |
| **`PlatformListeningChannelSource` is a static factory, not a `ListeningChannelSource`** — the suffix misleads | Standards | Cheap rename, left because it was outside the three fixes agreed with the user. |
| **`TransportServer` (221 lines) changes for three reasons** — accept loop, connection lifecycle, frame I/O | Standards | Possible Divergent Change. Splitting was judged premature at this size. |
| **`FrameDecoder.append(byte[])` overloads are used only by tests**; production uses the `ByteBuffer` one | Standards | Kept: coherent decoder API and the tests are real consumers. Flagging for a second opinion. |
| **Nothing asserts the headless/unprivileged criterion** | Spec | Verified by hand (uid 1000, no display vars). Arguably unassertable in-process. |

### One finding was verified false

The standards axis reported `.gitkeep` still tracked alongside the sources. It is
not — it appears as `D` in `0ed4d22` and is absent from `git ls-files`. The agent
read the `| 0` in the diffstat (an empty file being deleted) as presence. **A
reviewer should not re-raise it.**

## 5. What a final reviewer should attack first

1. **The JSON question.** ADR-0003's framing clause is only half-implemented and
   that is a conscious choice nobody has signed off. Decide where JSON validation
   lands and record it.
2. **`BoundListeningChannelSource`'s home.** Production code whose only
   production caller is a future platform.
3. **Whether the unasked-for hardening stays.** Three separate additions, all
   defensible, none requested.

## 6. Honest limits on what the green build means

- **A green build here is evidence about Linux only.** The Windows acquisition
  path is designed and unbuilt; `PlatformListeningChannelSource.forCurrentPlatform()`
  throws there, and its test is OS-guarded so it *skips* on Linux rather than
  passing. This is per issue #1's rule that a test which never runs and reports
  green is worse than no test.
- **systemd socket activation is not tested and cannot be.** What is tested is
  the *adoption logic* — given what systemd would hand over, the service takes
  it; given anything else, it refuses. The activation mechanism itself is covered
  by the manual checklist and the measurements in
  `docs/spikes/linux-service-activation.md`.
- **Stability was checked, not assumed.** The suite was run 10× consecutively
  with zero failures after the fixes.

## 7. A trap in this repo's test loop

`mvn -o -pl login-core surefire:test` **does not recompile**. A harness that used
it reported a phantom flaky failure for two rounds: the stack trace pointed at a
line that, in the edited source, was a `try {`. Use `mvn -o -pl login-core test`.
The failure it was masking was real and is fixed — the server can close so fast
after an over-cap prefix that the client's body write gets `EPIPE`, which the
test now treats as the property arriving early rather than as an error.

## 8. Reproducing

```bash
# from the worktree root, on branch wire-transport
mvn -o test                      # whole reactor
mvn -o -pl login-core test       # Seam 2 only — 44 tests, 1 OS-guarded skip
```
