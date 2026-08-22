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

---

# Code review — the CredentialStore and the Authenticator (issue #3, Seam 1)

Written for the same final reviewing agent, in the same shape as the wire report
above. It records what was built, what **two** review passes found, what was acted
on, and — more usefully — what was deliberately left open, so the next reviewer
spends its effort on unsettled ground.

**Read §7 first if you are the one merging this.** It is the only section that is
about neither standards nor spec, and it is where the surprises are.

## 1. Where the code is

| | |
|---|---|
| Branch | `worktree-issue-3-credential-store` (pushed to `origin`) |
| Commits | `ae0697b` (implementation), `2579235` (review pass 1), `699ea41` + `a8edb22` (review pass 2), `c63658a` (formatting) |
| Base / fixed point | `5879e5c`, the tip of `worktree-design-docs` |
| Diff to review | `git diff 5879e5c...HEAD` |
| Packages | `core.account`, `core.auth`, `core.authentication`, `core.ipc`, `core.session`, `core.store` in `login-core` |
| Build | `mvn clean verify` → 35 tests, 0 failures, 0 skipped on Linux |

**Branch base is neither `main` nor `dev-login`.** It was cut from
`worktree-design-docs` because the Maven skeleton, `CONTEXT.md` and the ADRs
existed only there. Since then `dev-login` has moved to `2c73169` and now carries
the wire (#2), `CLAUDE.md` and `docs/agents/` — **none of which this branch has
ever seen**. That asymmetry is not cosmetic; it produced the largest open finding
in this report (§4, "Google Java Style"). See §7.

## 2. What the ticket asked for

Issue #3, "CredentialStore and the Authenticator" — somewhere to keep Accounts and
the only component permitted to verify a password, in process, driven by request
objects, **no socket**. It establishes **Seam 1**: a harness that builds an
`AuthenticationService` with its store in a temporary directory and hands it
requests. Issue #3 also restates the naming rule: `AuthenticationService` is the
privileged process, the class that verifies a password is the `Authenticator`,
and the former must not be reused for the latter.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| `Bootstrap` creates the single `Administrator`, refused when one exists | met | `BootstrapTest` (5 tests), incl. `theRefusalSurvivesAServiceRestart` and `theSecondAdministratorIsNotCreatedByTheRefusedAttempt`. Enforced by a partial unique index in the schema, not only in code |
| `Granted` + opaque 128-bit `SessionToken` on a correct password, `Denied` on a wrong one | met | `AuthenticationTest.aCorrectPasswordIsGranted`, `theGrantedTokenIs128Bits`, `everyAuthenticationIssuesAFreshToken`, `aWrongPasswordIsDenied` |
| `Denied` reveals nothing about whether the Account exists | met | `theDenialRevealsNothingAboutWhetherTheAccountExists` asserts the two `Denied` values are `assertEquals`, plus `anAccountWhoseStoredHashCannotBeReadIsDeniedLikeAnyOther` for the damaged-hash case |
| An absent Account costs the same time as a real one | met, with a residue | `AbsentAccountCostsTheSameTest` — interleaved A/B sampling, medians, 25% tolerance. Residue in §4 |
| Argon2id via Password4j at OWASP minimums, stored as PHC strings | met | `ProductionHashingTest` (6 tests) incl. reading `password_hash` back through JDBC |
| A test pins **production** parameters; other tests use cheap ones on the identical path | met | `theProductionParametersMeetTheOwaspMinimums` pins against literals `19*1024 / 2 / 1`; `cheapAndProductionHashesAreVerifiedByTheSamePath` proves the path is shared both ways |
| Schema versioned, numbered migrations, reserved `SecondFactor` column | met | `CredentialStoreSchemaTest` (6 tests); `second_factor` asserted present via `PRAGMA table_info` |
| Refuses to start against a newer schema | met | `theServiceRefusesToStartAgainstANewerSchemaThanItUnderstands` sets `user_version = latest+1` and asserts `SchemaTooNewException` with the right found/understood values |
| Store file created owner-only | met on Linux only | `StoreFilePermissionsTest` (2 tests, `@EnabledOnOs({LINUX, MAC})`), incl. reasserting the mode when an existing store is reopened after being chmod'ed world-readable. Windows path in §6 |
| `SessionToken` never written to disk, never logged | **partial** | `SessionTokenTest.isNeverWrittenToDisk` + two `toString()` assertions. Its limits are a live finding — see §4 |

## 3. Design decisions a reviewer should judge, not rediscover

- **Equal cost comes from a reference hash built at construction.** The
  `Authenticator` hashes a 32-character random password it then throws away, and
  verifies against that whenever the named Account does not exist. This is the
  standard approach; its residue is in §4.
- **Parameters travel inside the PHC string**, so verification reads its cost
  from the stored hash and not from the `Authenticator`'s configuration. That is
  what lets the whole suite provision Accounts at `(256,1,1,32)` while still
  exercising the code that ships.
- **The migration list is written out, not classpath-scanned.** The order
  upgrades run in must not depend on how a packaged runtime enumerates resources.
- **Owner-only permissions are applied before SQLite touches the file.** A file
  SQLite creates itself inherits the umask, so the store is created empty and
  restricted first, and the mode is reasserted on every open.
- **One Administrator is a schema constraint**, `CREATE UNIQUE INDEX
  one_administrator ON accounts (role) WHERE role = 'ADMINISTRATOR'`, so the rule
  survives a code path that forgets to ask.
- **`SessionToken` has no `equals`, no `hashCode`, no `of`.** Private `byte[]`,
  `copyOfBytes()` returns a clone, `toString()` is exactly `SessionToken[redacted]`.
  Value-object reflexes are what put tokens into logs and maps.
- **Journal mode is left at the default rollback journal**, not WAL, with
  `synchronous = FULL`. A credential store trades throughput for durability, and
  WAL would add sidecar files whose permissions would each need the same care.
- **`ErrorCode.STORE_UNAVAILABLE` is beyond the spec's named set.** Added because
  the contract is that every request is answered; a store that cannot be read has
  to become a response rather than an exception thrown at the transport.

## 4. Two review passes: what was found and what was done

Reviewed twice by two independent agents each (standards axis, spec axis) against
`5879e5c...HEAD`.

### Pass 1 — acted on in `2579235`

1. **Package `daemon` is on `CONTEXT.md`'s `_Avoid_` list** for
   AuthenticationService, and three new test files had chosen it. Renamed main
   and test to `core.authentication`.
2. **`closeQuietly` could replace a migration failure with a close failure.**
   Replaced with `closeAfter(...)`, which attaches the close error as suppressed.
3. **Unused API removed**: `CredentialStore.schemaVersion()`,
   `Authenticator.parameters()`, `CredentialStoreException(String)`,
   `SessionToken.of`/`equals`/`hashCode`.
4. **`Argon2Parameters.PRODUCTION` had no main-code caller** — the pinning test
   pinned a constant nothing used. Added `AuthenticationService.open(Path)`
   defaulting to it, plus a test that reads the stored hash column back.
5. **A tautological test.** `theMigrationsAreNumberedFromOneWithoutGaps` asserted
   over a list generated by `IntStream.rangeClosed` and could not fail. Rewritten
   to assert the resource *file name* encodes its version and is on the classpath.
6. **`restrictWithoutPosix` threw when the platform refused** — which on Windows
   is always, so the service would not have started at all. Made best-effort and
   non-throwing.
7. **A speculative `Clock` parameter** removed; `Denied` given a redaction test;
   `CONTEXT.md` given the missing `Authenticator` and `SecondFactor` entries.

### Pass 2 — acted on in `699ea41` and `a8edb22`

1. **An unreadable stored hash escaped as an exception.** `Authenticator.verify`
   called `Argon2Function.getInstanceFromHash`, which throws
   `BadParametersException` on a hash that is not a readable Argon2id PHC string;
   `AuthenticationService.handle` catches only `CredentialStoreException`. A real
   Account with a damaged hash therefore produced a *different outcome* from an
   absent one — exactly what the denial must not reveal. Verify now falls back to
   the same reference-hash work, so the damaged Account is denied, denied
   identically, and denied after spending the same time. A bare `false` was
   rejected as a fix: it closes the outcome difference and opens a timing one.
   The fallback deliberately does not route back through `verify`, so an
   unreadable reference hash cannot become unbounded recursion.
   Test: `anAccountWhoseStoredHashCannotBeReadIsDeniedLikeAnyOther`, written
   first and observed failing with the real `BadParametersException`.
2. **`TOTP` in the schema comment**, against `CONTEXT.md`'s
   `SecondFactor _Avoid_: 2FA, MFA, TOTP, one-time code`. Beyond the word: the
   column is reserved for *whichever* second factor is chosen, and naming one in
   the schema is where a later reader takes the decision as already made. The
   comment now says no mechanism is named on purpose.
3. **Two unearned accessors.** `ServiceHarness.directory()` had no caller at all
   and was deleted; `storeFile()` had none outside the class and is private.
   `SchemaMigrations.resourceNames()` was public solely for one assertion while
   its neighbours are package-private for exactly that reason; it is now
   package-private too, and the test reaches it unchanged.

### Pass 3 — acted on in `c63658a`

**Google Java Style was not followed, in any of the 28 files.** `CLAUDE.md` names
the Google Java Style Guide as this repo's standard; the wire's code follows it —
2-space indent, static imports first, then one unsplit ASCII-sorted block — and
this branch used 4-space indent, non-static imports first, and blank lines
splitting the import block.

**Neither review pass could have found this**: `CLAUDE.md` does not exist at this
branch's base (`5879e5c`), only on `dev-login`. It surfaced while writing §7 of
this report, by comparing the two branches' sources directly. Fixed with
google-java-format 1.22.0 rather than by hand; whitespace, wrapping and import
order only, and the 35 tests pass unchanged.

No formatter plugin was added to the build. Whether the standard should be
mechanically enforced from now on is a project decision, not this ticket's, and
it is worth taking: nothing currently stops the next branch from diverging the
same way this one did.

### Deliberately not acted on — open for the next reviewer

| Finding | Axis | Why it was left |
|---|---|---|
| **`Granted` carries a `Role`.** The spec asks only for "`Granted` with an opaque 128-bit `SessionToken`"; handing the client a capability set is #5's business. The `role` column on `Account` is needed for Bootstrap; returning it over the seam was not asked for | Spec (scope creep) | Genuinely useful and genuinely unrequested. Reviewer's call |
| **`SessionTokenTest.isNeverWrittenToDisk` scans only for the raw 16 bytes.** A regression that persisted or logged the token hex- or Base64-encoded — the form anything would actually write — passes untouched | Spec | Real gap in a criterion the ticket states outright. The test does guard against vacuity (it asserts the file list is non-empty first), but it guards the wrong encoding |
| **"Never logged" rests on two `toString()` assertions.** No logging framework is wired up, so nothing pins the property against a future logger | Spec | Arguably unassertable until #6 introduces logging. Worth an explicit decision |
| **Equal cost holds only while stored Accounts carry the current parameters.** If parameters are later raised, an Account still hashed at the old cost is measurably cheaper than the absent-Account reference until it logs in once | Spec | Mechanism kept: it is the standard approach, and there is no Account to read parameters from when the name matches nothing. Residue documented in `verifyAgainstAbsentAccount`'s javadoc. The leaking case is untested |
| **`CredentialStore.restrictWithoutPosix` ships untested**, and its own javadoc calls it "designed, unbuilt and unverified" | Spec (scope creep) | The POSIX path satisfies the criterion on its own. Whether a best-effort Windows fallback belongs in `src/main` before anyone can run it is a real question |
| **The second-`Bootstrap` race returns the wrong code.** Only the `hasAdministrator()` pre-check yields `ADMINISTRATOR_EXISTS`; if the `one_administrator` unique index is what fires, it surfaces as `STORE_UNAVAILABLE` | Spec | Unreachable today — Seam 1 is single-threaded and #2's transport serialises per connection — but it is a real ordering bug waiting for concurrency |
| **`core.auth` and `core.authentication` split one glossary area**, and `auth` is an abbreviation the glossary never sanctions, shading toward `_Avoid_: auth server / auth helper` | Standards | Judgement call. `core.auth` came from the pre-existing skeleton, not from this work |
| **Six dead `.gitkeep` placeholders** remain in packages that now hold real sources: `account`, `auth`, `authentication`, `ipc`, `session`, `store`. The wire branch deleted its own | Standards | Left because the user scoped the last round to three fixes. A one-line `git rm`; note `authentication/.gitkeep` will collide with dev-login's `daemon/.gitkeep` — §7 |
| **Duplication across tests**: `PRAGMA user_version` read in three places, `OWNER_ONLY` spelled as a `fromString` in main and rebuilt by hand as `Set.of(...)` in the test, and `DriverManager.getConnection("jdbc:sqlite:" + …)` hand-rolled in three test classes | Standards | Real, and the permissions one can drift silently. Extracting across the main/test boundary needs a decision about where a shared helper lives |
| **Primitive Obsession on the account name** — a bare `String` in `Account`, `Authenticate`, `Bootstrap`, `CredentialStore.findByName`. ADR-0002 makes the name a secret with normalisation and blocklist rules, which currently have nowhere to live | Standards | Ties directly to #4. A type wanting to be born, but #4 should be the one to deliver it |
| **Account names match case-sensitively** (SQLite default), documented by `anAccountNameIsMatchedExactly` | Spec (undecided) | The spec decides nothing here. #4 owns naming rules and should settle it explicitly rather than inherit it |
| **`CONTEXT.md`'s `SecondFactor` entry claims "the administration UI shows the option visibly disabled"** — a UI assertion with nothing behind it | Spec | Written by this branch. Either #5 honours it or the sentence should go |
| Typo: `ProductionHashingTest.openingTheServiceAsProductionDoesStoresAnOwaspGradePhcHash` | Standards | Cosmetic |

### One finding should not be re-raised

The standards axis reported, in both passes, that **ADR-0002's blocklist of
predictable names (`admin`, `root`, `sa`, matched case-insensitively after
normalising separators and digit-for-letter substitutions) is not enforced**, and
it is not. But issue #4, "Password policy and Account naming rules", claims it
verbatim in its own acceptance criteria — including the full name list, the
normalisation rules and the requirement that the blocklist be a resource rather
than code. **It is scheduled work in another ticket, not a gap in #3.**

## 5. What a final reviewer should attack first

1. **The token-on-disk test.** The criterion is stated outright in the ticket and
   the test checks the one encoding nothing would ever use.
2. **Whether `Granted` keeps its `Role`.** Cheap to remove now, load-bearing for
   #5 later, and it decides how much the client is trusted to know.
3. **The `Bootstrap` race's error code**, if #2's transport is going to serve
   concurrent connections against one service instance.
4. **Whether the style standard gets mechanical enforcement.** This branch
   diverged from it silently for four commits, and nothing yet stops the next one.

## 6. Honest limits on what the green build means

- **A green build here is evidence about Linux only.** Ubuntu is the only
  validation machine. The Windows permission path is designed, unbuilt and
  unverified: `restrictWithoutPosix` is best-effort and never throws, and the
  permission tests are `@EnabledOnOs({LINUX, MAC})` so they *skip* on Windows
  rather than passing. Nothing here should be reported as working on Windows.
- **The timing criterion is statistical, and was made to bite.** Its teeth were
  checked twice by mutation — replacing the equal-cost branch with
  `.orElse(false)` failed at 19.7 ms vs 0.09 ms, and again at 17.8 ms vs 0.29 ms
  after the sampling changed. The code was restored both times.
- **The 25% tolerance is not slack, it is load tolerance.** An earlier version
  measured the two branches in separate phases and failed 1 run in 5 on a machine
  building several projects at once — machine load drifting between phases read
  as a branch difference. Samples are now interleaved, which makes drift hit both
  equally; 8 consecutive runs then passed. **If this test ever flakes, suspect the
  sampling before the mechanism**, and re-run the mutation check before believing
  the mechanism is fine.

## 7. The merge into `dev-login` — what actually happened

`dev-login` (`2c73169`) was merged into this branch and the result pushed. Before
that merge the two branches had never been compiled against each other. **They
now have: 79 tests, 0 failures, 1 skipped by the Windows OS guard.** What follows
records what the merge cost, including the two places this section previously
predicted wrong.

- **One textual conflict, this file.** `dev-login` already carried the wire's
  `Code_review.md`; this branch's copy was that file *plus* this section, so the
  resolution was to take this branch's — a strict superset. Nothing was lost.
- **`com.javafxlogin.core.ipc` is being written from both ends.** `dev-login` has
  the transport there (`FrameCodec`, `FrameDecoder`, `TransportServer`,
  `TransportClient`, `RequestHandler`, `ConnectionHandle`,
  `ListeningChannelSource` and friends); this branch adds the message types
  (`Request`, `Response`, `Bootstrap`, `Authenticate`, `Granted`, `Denied`, `Ok`,
  `ErrorResponse`, `ErrorCode`, `DeniedReason`) to the same package. No file
  names collided, so git merged them silently — it compiles precisely because the
  two halves do not reference each other at all.
- **The two halves do not join yet, and nobody owns the join.** The wire's seam is
  `byte[] handle(byte[] request, ConnectionHandle connection)`; this branch's is
  `Response handle(Request request)`. Nothing maps between them. That gap is
  precisely the "JSON framing unimplemented" question the wire report calls its
  largest open item (§3 and §5 of that report). **The merge did not close it — it
  made it the next thing that has to happen.** A green build on `dev-login` now
  means two halves that each work alone, not a service anyone can talk to. Writing
  that adapter, and deciding where JSON validation lives, is the first real task
  on top of this merge.
- **`core/daemon/.gitkeep` did *not* come back — this section predicted wrong.**
  Git followed the rename to `authentication` and `dev-login`'s untouched copy
  merged into it cleanly, so no forbidden empty package was resurrected. The six
  stale placeholders listed in §4 are still there and still worth deleting.
- **`CLAUDE.md` and `docs/agents/` existed only on `dev-login`.** They arrived
  cleanly, and they are the reason for the style finding in §4: this branch was
  written without them.
- **`dev-login` has three worktrees committed as gitlinks, and that is a bug
  nobody has noticed.** `.claude/worktrees/design-docs`,
  `.claude/worktrees/issue-3-credential-store` and `.claude/worktrees/wire` are
  tracked at mode `160000` — submodule entries, with no `.gitmodules` behind
  them, each pinned to a stale commit (this branch's entry points at `2579235`,
  four commits behind). They predate this merge and arrived through it untouched;
  nothing here created them and nothing here removed them, because deleting
  another branch's content was outside what was asked. **They should be deleted
  and `.claude/worktrees/` added to `.gitignore`** — a repo that tracks its own
  scratch worktrees will keep re-pinning stale commits into every merge.

## 8. Reproducing

```bash
# from the worktree root, on branch worktree-issue-3-credential-store
mvn clean verify                                    # whole reactor
mvn -pl login-core test                             # Seam 1 only — 35 tests
mvn -pl login-core test -Dtest=AuthenticationTest   # the denial rules, 10 tests
```

To re-check that the timing test still has teeth, replace the absent-Account
branch in `AuthenticationService.authenticate` with `.orElse(false)`, run
`AbsentAccountCostsTheSameTest`, and confirm it fails by roughly two orders of
magnitude before restoring it.

---

# Code review — the password policy and the Account naming rules (issue #4)

Written for a final reviewing agent, in the shape the two sections above use:
what was built, what the two-axis review found, what was acted on, and — more
usefully — what was **not**.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Commit | `e084430` (implementation and every review fix folded in) |
| Base / fixed point | `1f4f705` |
| Diff to review | `git diff 1f4f705..e084430` |
| Package | `com.javafxlogin.core.policy` in `login-core` |
| Build | `mvn -o test` → 144 tests, 0 failures, 1 skipped by the Windows OS guard |

The review ran against the work while it was staged, so its findings are not a
follow-up commit — they are inside `e084430`. Section 4 says which is which.

## 2. What the ticket asked for

Issue #4, "Password policy and Account naming rules". Blocked by #3, which is
closed; the rules are enforced at the service boundary and tested through the
Seam 1 harness that ticket established. The binding decision is ADR-0002, which
already required the name blocklist and says why.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Shorter than 12 or longer than 64 refused | met | `PasswordPolicyTest.aPasswordShorterThanTwelveCharactersIsRefused`, `…LongerThanSixtyFour…`, `…aPasswordAtEitherBoundaryIsAccepted` (12 and 64 both accepted) |
| No uppercase, number or special character refused (Passay) | met | `PasswordPolicyTest.aPasswordWithoutAnUppercaseLetterIsRefused`, `…ANumber…`, `…ASpecialCharacter…` |
| Bundled breach list, no network lookup ever | met | `PasswordPolicyTest.aPasswordOnTheBundledBreachListIsRefused`, `…theBreachListSeesThroughCase`; the list is `policy/breached-passwords.txt` and nothing in `login-core/src/main` opens a socket for it |
| Strength estimate returned, informative, never blocking (nbvcxz) | met | `PolicyEnforcementTest.assessReturnsAStrengthEstimateForDisplay`, `PasswordPolicyTest.aWeakEstimateDoesNotRefuseAPassword`, `…aRefusedPasswordIsStillEstimated` |
| Only a coarse band stored | met | `PolicyEnforcementTest.onlyTheCoarseStrengthBandIsStored`, `CredentialStoreSchemaTest.theAccountsTableRecordsTheCoarseStrengthBand`; `V002` constrains the column to the three names |
| No periodic expiry | met, weakly | `CredentialStoreSchemaTest.nothingInTheSchemaExpiresAPassword` — it guards the shape, not the behaviour |
| The thirteen names and the product's own refused | met | `AccountNamePolicyTest.aPredictableNameIsRefused` (parameterised over all thirteen), `…theProductsOwnNameIsRefused` |
| Case, separators, digit substitutions | met | `…matchingIsCaseInsensitive`, `…separatorsAreNormalisedAway`, `…digitForLetterSubstitutionsAreSeenThrough` |
| Whole name, not substring | met | `…aNameThatMerelyContainsABlockedOneIsAccepted` (`rosalind.sanders`, `testa.mercer`) |
| Blocklist extensible without a rebuild | met | `…aDeploymentExtendsTheBlocklistWithAFile`, `PolicyEnforcementTest.aDeploymentBlocklistBesideTheStoreIsEnforced` |
| Every refusal carries a reason | met | `PolicyEnforcementTest.aRefusalCarriesEveryReasonAtOnce`; `PolicyRefused` refuses to exist with an empty list |

## 3. Design decisions a reviewer should judge, not rediscover

- **`Assess` is a new request the ticket did not ask for.** The estimate has to
  reach the person typing, and `Bootstrap` answers `Ok`. The alternative was a
  client-side copy of the rules, which would drift from the ones that decide.
  It reads no Account, so it is not an oracle for which names are taken.
- **The canonical fold goes towards the digits, not away.** There is no answer
  to whether `1` is `i` or `l`, so both letters and the digit become `1` and the
  blocklist entries are folded the same way. Consequences, both accepted: the
  literal name `54` is refused because `sa` folds onto it, and `6` folds to `9`
  so `6uest` is refused too.
- **40 and 60 bits are this project's thresholds, not nbvcxz's.** nbvcxz's own
  top score starts at 35 bits, which is a floor for a login answering over a
  network and not for a store an attacker holds a copy of.
- **`V002` rather than an edit to `V001`.** No installation exists to migrate,
  so amending `V001` was available and was refused: it would have left any store
  already at version 1 stranded, and it is the second migration that first
  proves `applyTo` loops at all.
- **The band is not a score.** `strengthOf` reads bits and returns one of three
  names; nothing keeps the number.

## 4. Two-axis review: what was found and what was done

### Acted on, inside `e084430`

- Two test lines at 101 columns. Fixed by running `google-java-format 1.36.1`
  over every changed file — the same treatment `c63658a` gave the repo. It also
  rewrapped four hunks this work had wrapped by hand.
- `ipc.Assessed` was a field-for-field copy of `policy.Assessment`. `Assessed`
  now carries the `Assessment`, and `AuthenticationService.assess` stopped
  unpacking it.
- `AccountPolicy.bundled()` was public and used only by tests → package-private.
  `PasswordRules.MINIMUM_LENGTH` / `MAXIMUM_LENGTH` were package-private and
  used only inside the class → private.
- `PolicyResource.linesOf` did not say where it read from → `linesOfBundledList`.
- The field `PasswordValidator validator` used a name `CONTEXT.md` lists under
  _Avoid_ → `characterAndLengthRules`.
- `6uest` was accepted, because `g` folded to `9` and `6` folded to nothing.
  `6` now folds too, with a case in the parameterised test.
- `breached-passwords.txt` claimed a deployment could replace it — which needs a
  rebuild, unlike the name blocklist. The header now says so.
- `PolicyResource.linesOfFileIfPresent` catching only `NoSuchFileException` was
  an accident; it is now a documented decision. A deployment list that exists
  and cannot be read stops the service rather than quietly applying a weaker
  policy than the one configured.
- The schema test concatenated an Account name into SQL → bound parameter.

### Deliberately not acted on — open for the next reviewer

- **No ADR was written.** The offline breach list is the ticket's constraint
  rather than a decision this work made, and the thresholds are argued at the
  constants. A reviewer who thinks 40/60 deserves `docs/adr/0008-…` is not
  wrong; it was left because the choice is one line to change and lives beside
  its reasoning.
- **`ACCEPTABLE_ENTROPY_BITS` and `STRONG_ENTROPY_BITS` say "entropy"**, which
  `CONTEXT.md` lists under `PasswordStrength`'s _Avoid_. Kept: they name the
  estimator's own quantity, not the band. Overturnable.
- **A space is not a special character.** `EnglishCharacterData.Special` is
  Passay's set and excludes `0x20`, so `Aa1 zzzzzzzz` is refused while
  `Aa1£zzzzzzzz` passes. Passphrases are penalised. Kept because the criterion
  names Passay, and the deployment's stated policy is the one in the ticket.
- **Three rules are wider than the ticket asked.** `ACCOUNT_NAME_BLANK` (no
  criterion asks for it, and without it an Account could be named `""`); the
  fold covers `@ $ ! | +` as well as digits; breach matching ignores case.
- **The breach list is a seed of 76 entries**, not a corpus. Every entry recurs
  across public breach lists, and most are refused by the length rule first.
- **Nothing in `login-ui` consumes any of this.** No band is displayed and no
  refusal is worded; that is a later ticket, and the estimate currently reaches
  a seam and stops.

## 5. What a final reviewer should attack first

1. **The band mapping.** 40 and 60 bits are invented, and every Account's stored
   band depends on them. `Correct-Horse-1` estimates 34.5 bits and is therefore
   `WEAK` — check whether that is the message the wizard should send.
2. **The one password copy that cannot be zeroed.** `nbvcxz.estimate` takes a
   `String`. Everything else in this package works on `char[]` or a `CharBuffer`
   over it; that one `new String(password)` is the exception, and it is per
   keystroke if a UI calls `Assess` as the person types.
3. **`V002`'s `NOT NULL DEFAULT 'WEAK'`.** A row written before the column
   existed gets a band nobody estimated. The alternative was a nullable column
   and an `Optional` on `Account`; the conservative lie was chosen.
4. **Whether `Assess` belongs in the protocol.** It is the largest thing here
   the ticket did not ask for.
5. **The fold's collisions**, in both directions — a legitimate name that folds
   onto an entry is refused with no way to appeal.

## 6. Honest limits on what the green build means

- Every policy test provisions with the harness's cheap Argon2id parameters. The
  policy never touches hashing, so this is orthogonal — but it means no test
  here runs at production cost.
- The deployment blocklist is read once, at start-up. `…BesideTheStoreIsEnforced`
  proves it by restarting the harness; nothing proves what an edit does to a
  running service, because the answer is "nothing until it restarts".
- Nothing tests `Assess` under concurrency, though `TransportServer` serves
  connections on virtual threads. `Nbvcxz` is constructed per estimate over an
  immutable `Configuration`, and the Passay rules are stateless — that is
  reasoning, not evidence.
- `mvn -o` is offline by flag, which is not the same as proving no code path
  would reach the network. The claim rests on reading the two libraries' use,
  not on a sandbox that would have caught a call.

## 7. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o test                                            # whole reactor, 144 tests
mvn -o -pl login-core test -Dtest=PolicyEnforcementTest # Seam 1, the rules as enforced
mvn -o -pl login-core test -Dtest='*PolicyTest'         # the rules as units
```

To check the naming test still has teeth, delete the `case 'i', 'l', …` arm of
`AccountNameRules.folded` and confirm `digitForLetterSubstitutionsAreSeenThrough`
fails on `Adm1n` before restoring it.

---

# Code review — the walking skeleton (issue #5, Seams 1+2 joined and Seam 3)

Written for a final reviewing agent, in the same shape as the sections above:
what was built, what the two-axis review found, what was acted on and — more
usefully — what was **not**.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Commits | `316482a` (implementation), `25bb670` (review fixes) |
| Base / fixed point | `0c22a2e`, the tip of `dev-login` before this ticket |
| Diff to review | `git diff 0c22a2e...HEAD` — 44 files, +2198 / −39 |
| Packages | `…core.ipc`, `…core.authentication`, `…core.session` in `login-core`; `…ui.login` in `login-ui`; `…feature` in `protected-feature` |
| Build | `mvn -o test` → 200 tests, 0 failures, 1 skipped by the Windows OS guard |

## 2. What the ticket asked for

Issue #5, "Walking skeleton: authenticate and open the ProtectedFeature" — the
first thing a human can watch work, and the join of everything #2 and #3 built.
Parent spec is issue #1; the binding decisions are ADR-0002 (privileged
service), ADR-0003 (AF_UNIX and length-prefixed JSON) and ADR-0007 (no JPMS).

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| An Operator authenticates through the window and the ProtectedFeature opens | met | `LoginWindowTest.anOperatorAuthenticatesAndTheProtectedFeatureOpens` |
| The login stage closes rather than lingering behind the feature | met | `LoginWindowTest.theLoginStageClosesOnceAccessIsGranted` |
| A wrong password reveals nothing about whether the Account exists | met | `LoginWindowTest.aRefusalSaysNothingAboutWhetherTheAccountExists` (two refusals, identical text), `AuthenticationTest.theDenialRevealsNothingAboutWhetherTheAccountExists` |
| An Administrator is refused, **by the service** | met | `RoleEnforcementTest` (5 tests), `ServiceOverTheSocketTest.deniesTheAdministratorTheOperatorsRoleOverTheSocket`, `ServiceLoginGateTest.refusesTheAdministratorEvenWithTheRightPassword` — see §3 for how far it goes |
| A host reaches everything through `LoginGate`, handing over a view it knows nothing about | met | `LoginGate` (one abstract method, one default); `LoginWindowTest.handsTheHostTheSessionThatAdmittingSomeoneProduced`, `…nothingBehindTheGateIsBuiltUntilSomeoneIsAdmitted`; `ProtectedFeatureApplication` is one line |
| The client talks to the service over the real socket from #2 | met | `ServiceOverTheSocketTest` (7 tests), `ServiceLoginGateTest` (6 tests) |
| UI tests headless on Monocle against a fake `LoginGate` | met | `LoginWindowTest` extends `ApplicationTest`; `FakeLoginGate`; verified with `DISPLAY`, `WAYLAND_DISPLAY` and `XAUTHORITY` unset |
| `login-core` still has no JavaFX on its classpath | met | `NoJavaFxOnTheCoreClasspathTest` asks for `javafx.stage.Stage` and expects not to find it |

## 3. Design decisions a reviewer should judge, not rediscover

- **The Role moved into the request.** `Authenticate` now carries the Role the
  client asks to act in, and `Granted` no longer echoes one back. This is the
  ticket's load-bearing decision and the alternative was a Session registry in
  the service, which is #7's work. **Consequence for #12:** one login screen
  serving both Roles cannot know which Role the person holds before
  authenticating, so it must offer the choice — "one way in" becomes one screen
  with an explicit administration affordance, which story 38 arguably wants
  anyway. A reviewer who dislikes this should say so before the admin panel is
  built on it.
- **A Role mismatch is `Denied(AUTH_FAILED)`, indistinguishable from a wrong
  password.** Telling them apart would confirm which name is the Administrator
  — the one Account whose Role an attacker can guess. The check runs *after*
  the Argon2id verification so the refusal costs the same.
- **How far the exclusion goes today, exactly.** No Session for an Operator is
  ever issued against an Administrator's password. A patched client can still
  ask to act as an Administrator, be granted a Session, and draw the feature's
  window over a SecretVault that will not open for it (ADR-0005: no wrapped
  DataKey). That paragraph is in `ServiceLoginGate`'s Javadoc rather than only
  here, because it is exactly what a reader will otherwise overestimate.
- **The codec is hand-built, not annotation-driven.** The tree is read as data
  and every message is constructed by name against a closed set, so nothing the
  peer writes chooses a class to instantiate inside the privileged process.
  ADR-0003 refused RMI for this reason; Jackson databind is used only for its
  tree API, with `FAIL_ON_TRAILING_TOKENS` and `STRICT_DUPLICATE_DETECTION` on.
- **An unreadable payload costs the connection.** `MalformedMessageException` is
  unchecked so it can leave `RequestHandler.handle`, and the transport drops the
  connection — ADR-0003's rule one layer up. `TransportServer`'s comment about a
  throwing handler was amended to stop claiming that can only be a defect.
- **`AuthenticationService.handle` is now synchronised**, and `close()` with it.
  `RequestHandler` requires thread-safety across connections and the
  CredentialStore holds one JDBC connection. Serialising a single-desktop
  privileged process costs nothing worth having.
- **The gate keeps one connection.** A Session is bound to its connection, so
  `ServiceLoginGate` opens one lazily and keeps it; a dead one costs the attempt
  in flight and the next attempt reconnects (`reconnectsAfterTheServiceHasBeenRestarted`).
- **`ProtectedFeatureLauncher` exists because of ADR-0007.** JavaFX refuses to
  start from the classpath when the main class is an `Application` subclass. Any
  host product copying this module needs the same trick, so it is in the
  reference rather than in a footnote.

## 4. Two-axis review: what was found and what was done

Both axes ran as parallel sub-agents against `0c22a2e...HEAD`.

### Acted on

| Axis | Finding | What was done |
|---|---|---|
| Spec | The host never obtains a Session, though `CONTEXT.md` defines `LoginGate` as what a host calls to obtain one | `protect` now takes `Function<Session, Parent>`; the view is built from the Session. Test: `handsTheHostTheSessionThatAdmittingSomeoneProduced` |
| Standards | Speculative generality: the `Consumer<Session>` discarded its argument | Same change, from the other end — the argument is now consumed |
| Spec | Any `RuntimeException` other than `ServiceUnreachableException` left the window disabled with nothing said | Every failure re-enables the window and says something |
| Spec | Story 39 holds only halfway and nothing said so | The paragraph in `ServiceLoginGate` described in §3 |
| Spec | `account.orElseThrow()` leaned on an implicit invariant | `!verified \|\| account.isEmpty()` states it |
| Standards | Import order in `PolicyEnforcementTest` (Google style §3.3.3) — the only hard violation found | Fixed |
| Standards | `MessageCodec` and a test cited ADR-0003 for a message catalogue the ADR does not contain | Citation narrowed to what ADR-0003 actually decided; the `Error` name now cites `ErrorResponse` |
| Standards | Mysterious names: `finished`, `waiting`, `boundTo` | `showOutcome`, `showWaiting`, `boundToTheSocketNamedIn` |
| Standards | Two hunks wrapped where the formatter would join them | Joined |
| — (own) | `close()` could race a request in flight | Synchronised with `handle` |

### Not acted on, deliberately

- **`ServiceProcess.main` and the README recipe are scope creep (Spec).** True
  to the letter of the acceptance criteria. Kept, because "the first thing a
  human can watch work" is the ticket's own framing and a skeleton nobody can
  start is not one. #15 replaces the channel source, not the class. The
  installed path `/run/javafx-login/authentication.sock` is a constant with a
  Javadoc saying the installer owns it — **the most defensible thing to delete
  if a reviewer disagrees.**
- **`Assess`/`Assessed`/`PolicyRefused` encoding serves #6 (Spec).** A codec
  covering part of a closed set is a trap for whoever adds the next message. The
  types already existed; only their encoding is new.
- **The provisioning helper is duplicated across the module boundary
  (Standards).** `ServiceHarness.provisionOperatorIn` and
  `ServiceLoginGateTest.provisionOperator` are the same four lines, because
  `login-ui`'s tests cannot see `login-core`'s. Closing it means a test-jar in
  the build; four lines of test fixture did not seem worth that, and #10 deletes
  both when enrolment exists.
- **`(String accountName, char[] password)` is a data clump (Standards).**
  Agreed, and there is no term in `CONTEXT.md` for the pair a person types.
  Logged here as a glossary gap rather than fixed by inventing vocabulary — see
  §5.
- **`ServiceClient` and `ServiceEndpoint` are light Middle Men (Standards).**
  Both are seams named in ADR-0003 and the Seams section of #1; delegation is
  what they are for.

## 5. What a final reviewer should attack first

1. **The Role in the request.** §3's first bullet. Everything downstream of the
   login screen inherits it, and #12 is the ticket that pays if it is wrong.
2. **Whether the exclusion paragraph is honest enough.** It claims the refusal
   is worth something before the SecretVault exists. Read it against ADR-0005
   and disagree loudly if it oversells.
3. **The codec's refusals.** 27 tests say what it will not read. Look for a
   payload that is neither accepted nor refused — a shape that reaches a field
   accessor before the type is checked would be the bug worth finding.
4. **The gate's single connection.** It survives a service restart at the cost
   of one attempt. Whether *that* attempt should retry transparently rather than
   report an unreachable service is a real question, and it becomes #7's
   problem when a Session hangs off the same connection.
5. **A glossary gap:** no term for the name-and-password pair a person offers.

## 6. Honest limits on what the green build means

- **Nothing creates an Operator.** Both suites that need one write to the
  CredentialStore directly and say so. Run by hand, the pair therefore refuses
  every attempt — correctly — until #6 and #10 land. The README says this
  plainly rather than implying a demo that does not exist.
- **The end-to-end path is proven in two halves, not one.** #5 requires UI tests
  to drive a fake gate, so no test types a password into a window and reaches a
  real Argon2id hash. `ServiceLoginGateTest` covers gate-to-service and
  `LoginWindowTest` covers window-to-gate; the seam between them is an
  interface, not a test.
- **The window was watched by a human exactly once**, launched by hand against a
  running service. The 12 seconds it stayed up is the whole of the manual
  evidence.
- **`Session` carries a token and nothing else.** No expiry, no logout, no
  binding to the connection in code — all #7.
- **JavaFX warns "Unsupported JavaFX configuration: classes were loaded from
  'unnamed module'"** on every run. That is ADR-0007 being what it is, not a
  fault, and it will be in every log a host product ever reads.
- **Passwords become Strings.** `PasswordField.getText()` and the JSON encoding
  both make one; the `char[]` the window holds is blanked, the String is not.
  ADR-0003 accepts the password crossing in the clear, which is the same
  concession one layer down.

## 7. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o test                                              # whole reactor, 200 tests
mvn -o -pl login-core test -Dtest=RoleEnforcementTest    # the Administrator's exclusion, Seam 1
mvn -o -pl login-core test -Dtest=ServiceOverTheSocketTest  # Seams 1+2 joined
mvn -o -pl login-ui -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=LoginWindowTest                                 # Seam 3, headless
env -u DISPLAY -u WAYLAND_DISPLAY -u XAUTHORITY mvn -o test   # proves the display claim
```

To check Seam 3 still has teeth, make `LoginWindow.openProtectedFeature` skip
`loginStage.close()` and confirm `theLoginStageClosesOnceAccessIsGranted` fails
before restoring it.

---

# Code review — the first-run wizard (issue #6, Seams 1–3)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Commits | `88e50b6` (implementation), `a422741` (review fixes) |
| Base / fixed point | `0140a7e`, the tip after issue #5's review record |
| Diff to review | `git diff 0140a7e...HEAD` |
| Packages | `com.javafxlogin.core.ipc`, `com.javafxlogin.core.machine`, `com.javafxlogin.ui.login` |
| Build | `mvn -o test` → 248 tests, 0 failures, 1 skipped by an OS guard |
| New decision | ADR-0008 (`docs/adr/0008-first-run-is-authorised-by-peer-credentials.md`) |

## 2. What the ticket asked for

Issue #6, "First-run wizard: create the single Administrator" — the screen a
person sees the very first time the product runs, and the two guards behind it.
Parent spec is issue #1; it was blocked by #4 (the policy) and #5 (the walking
skeleton), both of which had landed.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Wizard appears instead of the login screen where there is no `Administrator` | met | `FirstRunWindowTest.theWizardOpensInsteadOfTheLoginScreenWhereThereIsNoAdministrator`; the choice is `LoginGate.protect` on `firstRunNeeded()` |
| Refused if an `Administrator` already exists | met | `BootstrapTest.isRefusedOnceAnAdministratorExists`, `…theRefusalSurvivesAServiceRestart`, `ServiceLoginGateTest.refusesASecondAdministrator` |
| Refused if the peer is not an operating-system administrator | met | `BootstrapTest.isRefusedWhenThePeerDoesNotAdministerTheMachine`, `…WhenTheOperatingSystemWillNotNameThePeer`, and over a real socket in `ServiceOverTheSocketTest.refusesToCreateTheAdministratorForAPeerTheMachineDoesNotAdminister` |
| Name field empty, nothing prefilled, placed or suggested | met | `FirstRunWindowTest.theAccountNameFieldIsEmptyAndSuggestsNothing` (asserts `promptText` too), `…thePasswordFieldIsEmpty…` |
| The rules from #4 applied; a refusal explains itself | met | `ServiceLoginGateTest.carriesBackEveryRuleThePolicyRefusedTheNameAndPasswordFor`, `FirstRunWindowTest.aPolicyRefusalNamesEveryRuleThatWasBrokenInWordsAPersonReads` (asserts no constant reaches the screen) |
| Warned the password cannot be recovered, with a password manager suggested | met | `FirstRunWindowTest.warnsThatThePasswordCannotBeRecoveredAndSaysWhereToKeepIt` |
| No recovery key, backup code or backdoor issued | met | `BootstrapTest.issuesNothingAlongsideTheAdministratorItCreated`, `ServiceLoginGateTest.issuesNothingAlongsideTheAdministratorItCreated` |
| After completion the login screen appears, and the `Administrator` can authenticate | **partial — see §5** | first half: `FirstRunWindowTest.theLoginScreenReplacesTheWizardOnceTheAdministratorExists`; second half: `ServiceLoginGateTest.theAdministratorTheWizardCreatesCanAuthenticate` — at the service, not through that screen |

Also verified by hand, which no test covers: the pair run as the README
describes, with the **real** `PosixMachineAdministrators` reading `/etc/group`
rather than an injected one — wizard needed, policy refusal with five named
violations, `Ok`, wizard no longer needed, second attempt `ADMINISTRATOR_EXISTS`,
`Administrator` granted a Session as an `Administrator` and denied one as an
`Operator`.

## 3. Design decisions a reviewer should judge, not rediscover

- **The peer is named by the kernel, at accept time.** `SO_PEERCRED` is read
  once in `TransportServer.Connection`'s constructor and cached, because the
  credentials are fixed at `connect()` and a handle asked after the peer died
  would answer nothing. `ConnectionHandle.peer()` returns `Optional<Peer>`;
  empty means the platform will not say, and is refused rather than trusted.
- **Policy and fact are split.** The handle reports who the peer is;
  `MachineAdministrators` decides whether that peer administers the machine, and
  is injected into `AuthenticationService`. That split is what lets both answers
  be tested: a suite cannot arrange real group membership, and one asserting
  against the real `/etc/group` would be asserting about the developer.
- **Two tests were passing by accident before this change.**
  `ServiceOverTheSocketTest` and `ServiceLoginGateTest` bootstrap over a real
  socket, and would have passed or failed on whether the developer happens to be
  in `sudo`. They now name the machine's administrators themselves, and name them
  by the account running the suite — so what is admitted is exactly what
  `SO_PEERCRED` reported about that very process, which is a stronger join of
  Seams 1 and 2 than a blanket "everyone".
- **Guard order: who before what.** `Bootstrap` settles the peer before it looks
  at the store, so a peer with no business here is told the same thing on a fresh
  install as on one set up years ago.
- **`AskIfBootstrapNeeded` is answered to anyone.** A client must choose a window
  before it knows anything else, and a fresh install reveals the answer the
  moment it draws one. `BootstrapTest.aPeerToldTheBootstrapIsNeededIsStillRefusedTheBootstrap`
  pins that being told is not being allowed.
- **The wire says `Bootstrap`; everything above says first run.** `Bootstrap`
  predates this ticket and is `core.ipc`'s settled name. `CONTEXT.md`'s term is
  `FirstRunWizard`, so `LoginGate`, the outcomes and the windows use it, and
  `ServiceLoginGate` is the one place the two vocabularies meet. Renaming the
  wire message was judged churn beyond this ticket.
- **The wizard takes the login window's stage and hands it back.** This is the
  first of two screens rather than a second window, so nothing is left behind and
  nobody has to go and find another window in order to log in.
- **`protect` asks which window to open on the JavaFX application thread.** One
  round trip, no hashing, and nothing has been drawn yet, so there is no window
  to freeze. On Linux the first connect is what socket-activates the service, so
  this is where a cold start is paid.

## 4. What the two-axis review found and what was done

**Standards axis.** No hard ADR violation. Acted on:

- `Peer` was load-bearing new language missing from `CONTEXT.md` while the same
  commit added `MachineAdministrator` and `FirstRunWizard`. Added.
- The `Bootstrap`/`FirstRun` split described above — `LoginGate` was offering
  `bootstrapNeeded()` beside a private `firstRunIsNeeded()`, and `WizardRefused`
  beside `FirstRunOutcome`. Unified as above.
- `FirstRunController` was `LoginController` retyped: the same virtual-thread
  block down to the verbatim comment, the same unreachable sentence, and a
  constant called `INTERRUPTED` catching a `RuntimeException`. Extracted to
  `GateAttempt`; the constant is now `UNANSWERED`.
- A `MessageCodec` comment saying "peer" where it meant "message", now that a
  `Peer` type shares the package.

**Spec axis.** Acted on:

- Nothing pinned that no recovery key is issued — true only because nobody had
  added a field. Now asserted on both sides of the socket.
- Nothing pinned the unreachable-at-startup branch. `NoServiceAtStartupTest`
  does, asked of its own stage.

## 5. Open ground — judge these rather than assume them

- **AC8's second half is met at the service, not through the screen the person
  is left looking at.** `ServiceLoginGate.admit` asks to act as an `Operator`,
  and the service refuses the `Administrator` there by design (issue #5, stories
  38–39). So the `Administrator` just created cannot log in through the window
  that replaces the wizard; they can authenticate, and are proven to, over the
  wire. The screen that will ask on their behalf is the administration UI, which
  is a later ticket. The README says this plainly rather than leaving someone to
  discover it. **This is the largest open question in the change**: either the
  criterion means what is built, or issue #6 wanted an administration entry point
  that its own body never describes.
- **`STORE_UNAVAILABLE` reaches the person as "could not contact the service".**
  A store the privileged process cannot read is shown with the wording for a
  service that is not running, so the remedy named is the wrong one. This was
  *not* changed: the pre-existing `admit` path already maps it the same way, and
  `ServiceUnreachableException`'s javadoc records that ADR-0002's three
  distinguishable startup failures are their own ticket. Fixing the wording here
  would invent a fourth vocabulary ahead of that ticket.
- **The group database is the machine's own local file.** ADR-0008 §Consequences
  states the limit: administrators from a directory service are not found, and
  `id -nG` was rejected rather than spawn a subprocess from a process running as
  root. If a reviewer disagrees, that is the paragraph to argue with.
- **`FirstRunRefusedReason` restates two of three `ErrorCode` constants.** Kept
  deliberately: `STORE_UNAVAILABLE` cannot reach the window, and a type carrying
  only what can happen means the controller's switch has no unreachable arm. A
  reviewer who wants `ErrorCode` passed through instead should say so.
- **No password confirmation field.** The ticket does not ask for one, and it was
  not added. Given that the password cannot be recovered and is typed once,
  blind, it is worth a ticket of its own — flagged rather than smuggled in.
- **`showWaiting(boolean)` is still written twice**, once per controller. The
  shape is shared but the controls are not, and a helper taking three nodes and a
  label read worse than the six lines it replaced.
- **Windows.** `MachineAdministrators.forCurrentPlatform()` throws there, as
  `PlatformListeningChannelSource` already does. `SO_PEERCRED` does not exist on
  that platform either, so `peer()` would be empty and the wizard refused. Both
  belong to the unbuilt Windows service ticket, and neither pretends otherwise.

---

# Code review — the Session lifecycle (issue #7, Seams 1–3)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Base / fixed point | `3db4193`, the tip after issue #6's review record |
| Diff to review | `git diff 3db4193...HEAD` |
| Packages | `com.javafxlogin.core.session`, `…core.authentication`, `…core.audit`, `…core.ipc`, `com.javafxlogin.ui.login` |
| Build | `mvn -o test` → 317 tests, 0 failures, 1 skipped by an OS guard |
| New decision | ADR-0009 (`docs/adr/0009-session-expiry-is-decided-when-someone-asks.md`) |

## 2. What the ticket asked for

Issue #7, "Session lifecycle: activity, expiry, logout and clock jumps" —
everything that ends a `Session`. Parent spec is issue #1, stories 45–54; it was
blocked by #5 (the walking skeleton), which had landed.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| A `Session` expires after a configured period without activity; the stage closes and returns to the login screen | met | `SessionExpiryTest.aSessionEndsAfterThePeriodWithoutActivity`, `…aSessionSurvivesUpToTheLastMomentOfThePeriod`; `SessionWindowTest.theWindowClosesAndTheLoginScreenReturnsWhenTheSessionEnds`, `…theLoginScreenSomeoneComesBackToIsEmpty` |
| `Operator` activity resets the countdown | met | `SessionExpiryTest.activityBuysAnotherWholePeriod`, `SessionLifecycleTest.activityStartsTheCountdownAgain`, `…askingAboutASessionIsNotActivity`, `SessionWindowTest.whatTheOperatorDoesIsReportedToTheService` |
| The `Administrator` can change the period globally, and disable expiry entirely | **partial — see §5** | `InactivityPeriodConfigurationTest` (seven tests, including `expiryCanBeSwitchedOffEntirely` and `theChangeSurvivesAServiceRestart`) — at the service, with no screen to do it from |
| An `Operator` can log out manually | met | `SessionLifecycleTest.anOperatorCanLogOut`, `…loggingOutTwiceIsNotAnOk`, `SessionWindowTest.loggingOutEndsTheSessionAndHandsThePersonBack` |
| A closed connection ends the `Session` immediately, with no heartbeat | met | `ServiceOverTheSocketTest.endsTheSessionOfAClientThatDisappears` (real socket, real client), `SessionLifecycleTest.aSessionEndsWithTheConnectionItWasGrantedOn` |
| Expiry is evaluated against both monotonic and wall-clock time | met | `SessionExpiryTest.aClockSetBackwardsDoesNotLengthenASession`, `…timeTheMonotonicClockDidNotCountStillCountsAgainstTheSession` |
| A wall-clock jump beyond tolerance expires the `Session` and records an `AuthenticationEvent` | met | `…aWallClockJumpBeyondToleranceEndsTheSession`, `…BackwardsBeyondToleranceEndsTheSessionToo`, `…aCorrectionSmallerThanTheToleranceIsNotAJump`, `…aClockJumpIsRecordedAsAnAuthenticationEvent` |
| A second `Authenticate` while a `Session` is live is refused, and the existing one kept | met | `SessionLifecycleTest.aSecondAuthenticationIsRefusedWhileASessionIsLive`, `…theRefusalIsMadeWithoutLookingAtAnyAccount`, `ServiceOverTheSocketTest.refusesASecondSessionOverTheSocket`, `LoginWindowTest.aSessionAlreadyOpenIsNotShownAsAWrongPassword` |
| The `SessionToken` still never touches disk | met | pre-existing `SessionTokenTest.isNeverWrittenToDisk` walks the whole directory, so it now covers the new event log too; `SessionExpiryTest.nothingRecordedAboutASessionCarriesItsToken` |

## 3. Design decisions a reviewer should judge, not rediscover

- **Expiry is lazy, and that is ADR-0009.** No timer in the privileged process:
  every request carrying a `SessionToken` first asks whether the live `Session`
  has run out. Nothing acts on an expired `Session` until a client asks, and the
  client asks anyway. The ADR records the four rejected alternatives, including
  the heartbeat and `CLOCK_BOOTTIME`.
- **A resumed machine and a clock someone set are the same event here.** Both
  make the wall clock run ahead of the monotonic one, and a JVM cannot tell them
  apart. Both end the `Session` as `CLOCK_JUMPED`, and `SessionEndedText` words
  it as both possibilities rather than guessing between them.
- **The tolerance is not the security control.** Taking the *longer* of the two
  measures is: a clock set backwards shortens the wall measure and leaves the
  monotonic one untouched, so it buys nothing. One minute is only where drift
  stops being ordinary.
- **The guard asks once per countdown, at the moment the service named.** Every
  answer carries how long is left, so the guard never computes a deadline. Its
  reporting cadence is a quarter of that, capped at twenty seconds — so an
  `InactivityPeriod` shorter than the cap cannot coalesce away the activity of
  someone who is working.
- **A `SessionToken` is not a bearer credential.** It names a `Session` only on
  the connection that `Session` was granted on; presented on another it is
  `NO_SUCH_SESSION`, which is also what an unknown token gets.
- **`Sessions` has its own monitor, not the service's.** The close listener runs
  on whichever thread noticed the connection go and must not queue behind an
  Argon2id hash. The monitor is held long enough to read two clocks.
- **The gate owns the window an `Operator` works in.** `SessionWindow` places the
  host's view untouched inside a `BorderPane` with one control above it. Two
  things have to happen there that no host should write and none should be able
  to forget: somewhere to log out, and closing when the service says the
  `Session` is over. `login.css` rules are all scoped, so the gate's stylesheet
  cannot restyle what the host handed over.
- **`admit` returns `Admission` rather than `Optional<Session>`.** Story 54's
  refusal has a different remedy from a wrong password, and `DeniedReason`'s
  javadoc always said the set grows when the client must act differently. The
  refusal reveals nothing: no Account is read to produce it, and a live `Session`
  is visible to anyone who can see the screen it is open on.
- **The audit log is a seam with the smallest honest thing behind it.** #9 owns
  the HMAC chain, rotation and export, and `AuthenticationEventLog`'s javadoc
  says so. What is here writes one flushed CSV line per event and swallows what
  it cannot write, because story 81 says a full disk must not lock everyone out.

## 4. What the two-axis review found and what was done

**Standards axis.** No ADR contradiction; ADR-0009 honoured point for point.
Acted on:

- `timeout` — the first entry on the new `InactivityPeriod` `_Avoid_` list — had
  reached a test's javadoc. Reworded.
- `GateWindow`'s javadoc still said the login screen "is replaced by nothing".
  This change puts it back. Corrected.
- Repeated `switch` over `SessionOutcome` in four `AuthenticationService`
  methods, with one arm written three times. Gathered into
  `onTheSessionNamedBy`, which is now the shape of every request only a live
  `Session` may make.
- `expireAnySessionThatIsDue()` returned a value its name never mentioned. Split
  into `theConfiguredPeriod()` and a void `expireAnySessionThatIsDue(period)`.
- Three lines wrapped where they fit inside 100 columns.

**Spec axis.** Every acceptance criterion checked one by one; the table in §2 is
its verdict. Acted on:

- The `SessionGuard`'s twenty-second coalescing was a constant, while
  `InactivityPeriod.of` accepts any positive duration — so a period of ten
  seconds would have expired someone who was working. The cadence now follows
  what the service says the `Session` has left.
- `Sessions.open` registered a close listener per admission, so a client logging
  in and out on one connection accumulated them. One per connection now.
- A window closed with its own decoration left the guard asking. `setOnHidden`
  stops it.

## 5. Open ground — judge these rather than assume them

- **The `Administrator` can configure the period, but has nowhere to do it
  from.** `ChangeInactivityPeriod` is complete and tested at Seams 1 and 2, and
  unreachable from the shipped client: `ServiceLoginGate.admit` asks to act as an
  `Operator`, and the service refuses the `Administrator` there by design. This
  is the *same* open question issue #6's review recorded as its largest, and it
  resolves the same way — the administration entry point is issue #12, whose
  first acceptance criterion is that the panel is reachable only by an
  `Administrator` `Session`. Adding a gate method and a second login path here
  would be building #12 ahead of #12. **Flagged rather than smuggled in**: if a
  reviewer thinks #7 owned that entry point, this is the paragraph to argue with.
- **`AuthenticationEventType.CONFIGURATION_CHANGED` is early.** #7 only requires
  the clock jump to be recorded. `CONTEXT.md` has always called a configuration
  change an `AuthenticationEvent`, and this ticket introduces the only one that
  exists, so it is recorded. A reviewer who wants the audit ticket to own every
  event type should say so.
- **`Admission` and `SessionStatus` are not in `CONTEXT.md`.** They are host-
  facing and `public`, which is the argument for adding them. They were not,
  because `FirstRunOutcome` and its three implementations — equally public,
  equally host-facing — are not there either: the glossary holds the domain's
  nouns, and these are the closed sets of answers a window switches on.
  `InactivityPeriod`, which *is* a domain noun, was added.
- **The end-of-`Session` sentence travels as a `String`**, from `SessionGuard`
  through to `LoginController`, with `""` meaning "nothing to say". A type was
  considered and refused: the value is a label's text at every hop, and a
  `Label` with no text is already how "nothing to say" is spelt in JavaFX.
- **One listener per connection is not one listener per lifetime.** Two clients
  taking turns on two connections still add a listener to each on every turn.
  Bounded by successful authentications, each of which costs an Argon2id
  verification, so it is untidiness rather than an exposure — but it is not zero.
- **Closing the feature window does not log out.** It stops the guard; the
  `Session` then expires as any unattended one does, or ends with the connection
  when the process exits. Sending a logout from an `onHidden` handler was judged
  worse than relying on the mechanism this ticket exists to build.
- **A kiosk `Session` is not ended by a clock jump.** Switching expiry off
  switches off both rules, deliberately: a kiosk that logged itself out because
  the machine's time was corrected is a kiosk nobody could keep running.
  ADR-0009 §Consequences states it.
- **`Sessions` holds one slot, and `Authenticate` refuses rather than replaces.**
  The alternative — the newest authentication wins — would let anyone who can
  type a password throw out the person working. Story 54 says which one is kept
  and this follows it, but it is a product decision worth seeing.

---

# Code review — Lockout (issue #8, Seams 1 and 3)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Base / fixed point | `2c3b1d7`, the tip after issue #7's review record |
| Diff to review | `git diff 2c3b1d7...HEAD` |
| Packages | `com.javafxlogin.core.account`, `…core.authentication`, `…core.store`, `…core.ipc`, `…core.audit`, `com.javafxlogin.ui.login` |
| Build | `mvn -o test` → 351 tests, 0 failures, 1 skipped by an OS guard |
| New decision | ADR-0010 (`docs/adr/0010-lockout-is-persisted-and-says-so.md`) |
| New migration | `V004__lockout.sql` — two columns on `accounts`, two `configuration` rows |

**How this review was run.** The two axes were meant to run as parallel
sub-agents, as issues #6 and #7 did. Both were killed by a session limit before
they read anything, so the review below was carried out in one context instead.
A final reviewer should weigh that: the axes did not check each other, and the
same agent that wrote the code judged it.

## 2. What the ticket asked for

Issue #8, "Lockout that survives a service restart" — parent spec issue #1,
stories 40–44, plus story 89 (no rate-limiting state in memory). It was blocked
by #5 (the walking skeleton), which had landed.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| An Account that fails authentication a configured number of times enters `Lockout` | met | `LockoutTest.theConfiguredNumberOfFailuresLocksTheAccount`, `…theNumberOfFailuresThatLocksIsWhateverTheStoreSays`, `ConfigurationTest.aFreshStoreLocksAnAccountOutAfterFiveFailuresForAQuarterOfAnHour` |
| `Lockout` survives a restart of the `AuthenticationService` | met | `LockoutTest.theLockoutSurvivesARestartOfTheService` (closes the service and reopens it against the same files) |
| A locked Account receives a distinct refusal saying so and for how long | met | `LockoutTest.aLockedAccountIsToldSoAndForHowLong`, `…theRefusalSaysWhatIsLeftOfTheLockoutRatherThanItsWholeLength`; over a real socket, `ServiceLoginGateTest.carriesALockoutBackWithTheWaitTheServiceDecided`; as a person meets it, `LoginWindowTest.aLockedAccountIsToldHowLongItHasToWait` |
| `Lockout` state is in the store the service owns and is unreachable by an unprivileged process | met | `LockoutTest.everyFileTheLockoutIsWrittenToIsOwnerOnly` walks the whole directory after a Lockout; `StoreFilePermissionsTest` still asserts the mode on create and reopen |
| Every write is flushed, since the service does not run continuously | met | `LockoutTest.theLockoutIsWrittenToTheStoreAtOnceRatherThanWhenTheServiceStops` reads the row over a second JDBC connection while the service still holds its own; `PRAGMA synchronous = FULL` and autocommit are what make that true |
| The `Administrator` can clear a `Lockout` | met at the service | `LockoutTest.anAdministratorCanClearALockout`, `…clearingALockoutLeavesNothingCountedAgainstTheAccount`, `…anOperatorsSessionCannotClearALockout`, `…clearingALockoutForAnAccountThatDoesNotExistIsRefused`, `…aTokenThatNamesNoSessionClearsNothing` — **no screen, see §5** |
| Entering and clearing each record an `AuthenticationEvent` | met | `LockoutTest.enteringALockoutIsRecorded`, `…clearingALockoutIsRecorded` |
| A successful authentication resets the failure count | met | `LockoutTest.aSuccessfulAuthenticationForgetsTheFailuresBeforeIt`, `…theFailuresBeforeASuccessfulAttemptNeverAddUpToALockout` |

## 3. Design decisions a reviewer should judge, not rediscover

- **The state is two columns on the Account, and that is ADR-0010.** The service
  stops after five idle minutes (ADR-0002), so a counter in memory is one an
  attacker clears by waiting — story 89 says so outright. It is in the
  `CredentialStore` rather than a file of its own because that buys the same
  directory, owner and mode without a second thing to keep consistent with the
  Accounts it is about.
- **Nothing is remembered about a name no Account holds.** Counting failures
  against whatever was typed would close the oracle below, and would pay with a
  row in the privileged store for every string ever typed at a login screen —
  one of which is eventually somebody's password in the wrong box, which is the
  reasoning story 77 already applies to the audit log.
  `LockoutTest.aNameNoAccountHoldsIsNeverLockedOutAndIsNeverWrittenDown` reads
  the store's bytes and asserts the name is not in them.
- **`LOCKED_OUT` is an oracle, deliberately, and it is priced.** It is the one
  answer this service gives that says something about an Account. Five wrong
  guesses at a name confirm the name is real — at the cost of one Argon2id
  verification per guess, the Account locked for a quarter of an hour, and an
  `ACCOUNT_LOCKED_OUT` line in the audit log. Story 43 asks for it, because the
  alternative is a person retyping a correct password for fifteen minutes at a
  screen insisting it is wrong. ADR-0010 states the trade and the six options it
  beat, including the two that would remove the leak.
- **The Lockout is applied after the Argon2id verification, not instead of it.**
  Every refusal therefore costs the same — locked, wrong and absent alike — so
  the stopwatch ADR-0002 keeps away from the account list is kept away from the
  list of locked Accounts too. Skipping the work would save nothing: an attempt
  costs one hash whatever name it names. This is why
  `AbsentAccountCostsTheSameTest` still passes, and why it now raises the policy
  out of its own way rather than switching Lockout off (see §4).
- **A correct password in the wrong Role counts as a failure.** An Account that
  could never be locked out would be the one an attacker picks out of the list
  by failing at it all afternoon — and the Account whose Role is guessable is
  the Administrator's. `LockoutTest.aRightPasswordInTheWrongRoleCountsTowardsTheLockout`.
- **Timed by the wall clock alone, and never outlasting its configured length.**
  A monotonic reading is a count from an origin the process chose and means
  nothing after a restart, so ADR-0009's second clock cannot be used here. A
  Lockout that claims to end further away than the configured length is read as
  over: whoever set the clock back is a MachineAdministrator who can rewrite the
  file directly, so it costs nothing already lost, and the alternative is
  refusing a person until a date a clock error invented.
- **`Denied` grew one optional field rather than the wire growing a response.**
  `DeniedReason`'s javadoc had always said the set grows when a client must act
  differently, and issue #1's protocol sketch names `LOCKED_OUT` there. The
  record refuses to be built any other way: a Lockout always says how long, and
  nothing else ever does — enforced in the compact constructor and at the codec
  (`MessageCodecTest.refusesARefusalWhoseReasonAndWaitDoNotAgree`).
- **`Lockouts` is a sibling of `Sessions`, and package-private.** Four methods —
  `refusalOf`, `failed`, `succeeded`, `clear` — and the service turns what they
  answer into responses and events, exactly as it does for `Sessions`. It is not
  `public` because nothing outside the package has any business with it.

## 4. What the two-axis review found and what was done

**Standards axis.** No ADR contradiction; ADR-0002 and ADR-0009 honoured, and
ADR-0010 written for the decisions this ticket had to make itself. `CONTEXT.md`'s
`_Avoid_` list for `Lockout` (ban, throttle, block) is respected throughout, and
`LockoutPolicy` was added to the glossary because it is new load-bearing
language. Acted on:

- The Role guard was written twice once `ClearLockout` arrived — the same
  `live.role() != ADMINISTRATOR` cascade in two methods. Gathered into
  `onlyAnAdministrator`, which is now the shape of every request only an
  Administrator may make, as `onTheSessionNamedBy` is for every request only a
  live Session may make.
- The codec had `expiresInMillis` read and written by hand, and `lockedForMillis`
  would have been the second copy of it. Both now go through one pair of
  `millis` helpers, so "an absent duration is an explicit null" is written once.
- `CredentialStore.inactivityPeriod()` and the new `lockoutPolicy()` would have
  duplicated the settings lookup. Extracted `setting(name)`; the missing-setting
  and not-a-value behaviour `ConfigurationTest` already pinned is unchanged.
- Five lines wrapped where they exceeded 100 columns, and one test's imports
  re-sorted.

**Spec axis.** Every acceptance criterion checked one by one; the table in §2 is
its verdict. Acted on:

- Criterion 4 ("not reachable by an unprivileged process") had only indirect
  evidence — `StoreFilePermissionsTest` asserts the store's mode, but nothing
  asserted that a Lockout does not write anywhere else.
  `LockoutTest.everyFileTheLockoutIsWrittenToIsOwnerOnly` now walks the whole
  directory after a Lockout, so a later build that put this state in a file of
  its own fails rather than shipping a Lockout an Operator can delete.
- The upgrade path was untested for this migration.
  `CredentialStoreSchemaTest.anAccountFromAnEarlierSchemaHasFailedNothing`
  builds a store at V001, migrates it, and asserts the Account comes out having
  failed nothing — a migration that left it counted as anything else would lock
  someone out on the strength of a number nobody counted.
- `AbsentAccountCostsTheSameTest` fails at one Account twenty times, so at the
  shipped policy it would have been locked out halfway through the warm-up and
  every later sample would have timed a refusal an absent name can never
  receive. It now raises the number of failures out of the measurement's way,
  with the reason written where it is done. **This is a real interaction worth a
  reviewer's eye**: the equal-cost property is now asserted only for the
  attempts before a Lockout, which is the only window in which it can be
  asserted at all.

## 5. Open ground — judge these rather than assume them

- **The `Administrator` can clear a `Lockout`, but has nowhere to do it from.**
  `ClearLockout` is complete and tested at Seam 1, and unreachable from the
  shipped client: `ServiceLoginGate.admit` asks to act as an `Operator`, and the
  service refuses the `Administrator` there by design. This is the *same* open
  question issues #6 and #7 recorded, and it resolves the same way — the
  administration panel is issue #12. **Flagged rather than smuggled in.**
- **The single `Administrator` can lock themselves out, and only they can clear
  it.** Five failed attempts at the Administrator's own name — including the
  correct password offered at the login screen, which asks for the Operator Role
  — refuse them for fifteen minutes, and the request that would release them
  needs an Administrator Session nobody can obtain in the meantime. It ends by
  itself, and the alternative (an Account that never locks) is the oracle §3
  refuses. A reviewer who wants the Administrator exempted should argue with
  this paragraph rather than assume it was missed.
- **Nothing in this build changes the `LockoutPolicy`.** The migration writes
  five failures and fifteen minutes; the store reads them again on every
  decision, so the screen that will change them is a change of caller, not of
  shape. No setter was added, because a setter nobody calls is a constant with
  extra steps — the suite writes the `configuration` row directly instead
  (`ServiceHarness.lockoutPolicyIs`), which is what a deployment would do today.
- **`Denied` and `NotAdmitted` carry the same invariant twice.** The UI keeps its
  own vocabulary — `NotAdmitted` has always carried the service's `DeniedReason`
  rather than being the wire type — and the duplicated rule is what makes
  `lockedFor().orElseThrow()` honest in `LoginController`. Considered and kept;
  worth disagreeing with if you think the client should carry the `Denied`.
- **A Lockout is not extended by attempts made during it.** Nothing is counted
  while an Account is refused, because nothing is verified. Someone hammering a
  locked Account gets the same fifteen minutes rather than an ever-growing one,
  which is deliberate: the alternative lets anyone who can reach the login
  screen keep an Operator out indefinitely.
- **The wait a person reads is rounded up to whole minutes, floored at one.**
  Fourteen and a half minutes reads as fifteen. A screen that said "one minute"
  and then refused someone would be worse than a screen that overstates by
  thirty seconds; `LoginController.waitOf` is where to argue.
- **`FailedAuthentications` is not in `CONTEXT.md`.** It is the store's record of
  what leads to a `Lockout` rather than a domain noun of its own, and the
  glossary holds the nouns. `LockoutPolicy` was added, because a deployment
  configures it and ADR-0010 rests on it.
- **The event log names the Account, not the Administrator who cleared it.**
  `AuthenticationEvent` carries one subject, there is exactly one Administrator,
  and the Account released is the name a reader is looking for. If the audit
  ticket (#9) gives events an actor, `LOCKOUT_CLEARED` is the first that wants
  one.
- **Nothing here slows an offline attack on a stolen hash.** That is Argon2id's
  job at the parameters ADR-0002 pins, and reading a Lockout as protection
  against it would be reading it as a strength it does not have. The ticket says
  this in its own words, and so does `LockoutPolicy`'s javadoc.

---

# Code review — Audit log (issue #9)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Base / fixed point | `4671ab8`, the tip after issue #8's review record |
| Diff to review | `git diff 4671ab8...HEAD` |
| Packages | `com.javafxlogin.core.audit`, `…core.authentication`, `…core.ipc`, `…core.store`, `com.javafxlogin.ui.login` |
| Build | `mvn -o clean test` → 338 core tests, 49 UI, 1 feature, 0 failures, 1 skipped by an OS guard |
| New decision | ADR-0011 (`docs/adr/0011-the-audit-log-is-chained-bounded-and-only-copied-out.md`) |
| New migration | none — nothing about the record lives in the CredentialStore |

**How this review was run.** The two axes were meant to run as parallel
sub-agents. The Standards axis completed and its report is §4 below, largely
verbatim. The Spec axis was killed by a session limit before it read anything,
so that axis was carried out in this context instead — by the same agent that
wrote the code. A final reviewer should weigh that: §2's table is a self-assessment,
and §5 is where it says so. This is the second ticket in a row where a review
sub-agent died this way (#8's record says the same).

## 2. What the ticket asked for

Issue #9, "Audit log: write-only, HMAC-chained AuthenticationEvents" — parent
spec issue #1, stories 73–81. It was blocked by #5 (the walking skeleton), which
had landed. The `audit` package already held `AuthenticationEvent`,
`AuthenticationEventLog` and a `FileAuthenticationEventLog` that wrote a plain
CSV line: issues #7 and #8 put them there and both said in as many words that the
chain, the rotation and the export belonged to this ticket.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Authentication attempts, `Lockout`s, Account changes, configuration changes and exports all recorded | met, for everything this build can do | `AuthenticationEventRecordingTest` — `aSuccessfulAuthenticationIsRecordedAgainstTheAccount`, `aWrongPasswordIsRecordedAgainstTheAccountItWasOfferedTo`, `theRightPasswordInTheWrongRoleIsRecordedAsSuch`, `beingRefusedWhileLockedOutIsRecorded`, `anAttemptMadeWhileTheMachineIsBusyIsRecordedAgainstNobody`, `creatingTheAdministratorIsRecorded`, `exportingIsItselfRecorded`; `LockoutTest.enteringALockoutIsRecorded`, `…clearingALockoutIsRecorded`; `InactivityPeriodConfigurationTest.aChangeIsRecordedAgainstTheAdministratorWhoMadeIt` |
| Entries are CSV, ISO-8601 **with a timezone** | met | `FileAuthenticationEventLogTest.theTimestampCarriesATimezone` matches the offset or `Z` |
| A nonexistent Account is recorded against a fixed placeholder, never the typed string | met | `…anAttemptAgainstANameNobodyHoldsIsRecordedAgainstAPlaceholder` fails at a name shaped like a password and asserts the typed string is not in the file; `noAccountCanBeCalledWhatStandsInForOneThatIsMissing` proves the placeholder is refused as an Account name |
| Each entry chained under an HMAC; a deletion or edit in the middle is detectable | met | `anEditedEntryIsFoundAtTheNextExport`, `anEntryRemovedFromTheMiddleIsFoundAtTheNextExport`, `aWholeFileRemovedFromTheMiddleIsFoundAtTheNextExport`, `anUntouchedRecordExportsWithItsChainIntact`, `theChainCarriesOnAcrossARestartOfTheService` |
| Every entry flushed to disk as it is written | met, with the caveat in §5 | `anEntryIsOnTheDiskBeforeRecordingReturns`; the mechanism is `FileChannel.force(true)` on every entry |
| The log rotates at a bounded size and count | met | `theRecordRotatesAndCannotGrowWithoutBound` — five files kept, under six megabytes in all |
| **Authentication still succeeds when the log cannot be written** | met | `authenticationStillSucceedsWhenTheRecordCannotBeWritten` (the record's path is a directory); `recordingSwallowsAFileItCannotWrite` at the unit level |
| The `Administrator` can export; the application never reads it back for display | met at the service — **no screen, see §5** | `anAdministratorCanExportTheRecord`, `theExportHoldsEveryEntryOldestFirst`, `theExportIsOwnerOnly`, `anOperatorCannotExportTheRecord`, `aTokenThatNamesNoSessionExportsNothing`, and four refusals of a destination |
| No password, `SessionToken` or enrolment secret in an entry | met as far as it can be | `noPasswordEverReachesTheRecord`, `noSessionTokenEverReachesTheRecord`; there is no enrolment secret in this build (issue #10) |

## 3. Design decisions a reviewer should judge, not rediscover

- **The chain value is the *first* field of the line, not the last.** An Account
  name is the only field a person controls; it is written last so that finding
  where the chain value ends never means parsing back through a quoted name
  somebody else chose. For the same reason a control character in a name is
  folded to a space — one event is one line, and an Account name does not get to
  decide how many entries there are.
- **The chain value the next entry follows is read from the disk on the first
  event after a start.** It has to be. The service stops after five idle minutes
  (ADR-0002), so a chain that began again on each start would break after every
  login, which reads exactly like tampering. Reading one field of one line is not
  reading the record back: no event ever enters the application.
- **`AuthenticationEventArchive` is a second interface, not two more methods on
  `AuthenticationEventLog`.** One object implements both, and `open` hands it
  over twice. Whoever records an event holds a reference that cannot read one
  back; only the one request an Administrator must be authenticated to make holds
  the other. The write-only promise of story 74 stays exactly as narrow as it was.
- **The chain is verified at exactly one moment — when the record is exported.**
  That gives the check a caller in production rather than only in the suite, and
  it is the moment a person is in a position to do something about the answer.
- **The export leaves as a file and never as a response.** ADR-0003 caps a frame
  at a megabyte and the record may be five; and a response carrying events is one
  refactor away from the viewer story 74 exists to prevent. The response carries
  two numbers: how many entries, and whether the chain held.
- **The exported copy is owner-only.** An export the logged-in operating-system
  account can read is the account list and the pattern of every login handed to
  the person ADR-0002 keeps them from. Reading the copy costs the privileges the
  service runs with. This is the decision most worth disagreeing with, and
  ADR-0011 states it rather than burying it.
- **The service refuses a destination that is relative, whose directory is
  absent, or that is inside its own directory** — one `ErrorCode` for all of
  them, because every one is answered by choosing another path and a privileged
  process that reported which paths exist would be answering questions nobody
  asked. Whether something is *already* there is decided by the operating system
  at `O_CREAT|O_EXCL`, not by a check made first and acted on afterwards, so a
  symbolic link planted in between goes nowhere.
- **A failed authentication says in the record why it failed** — wrong password,
  no such Account, wrong Role, locked out, machine busy — where the client is
  told only `AUTH_FAILED`. Issue #1 asks for exactly this in its protocol sketch.
  Whoever exports the file has already proved they administer the deployment.
- **An entry that could not be written is not chained onto.** The in-memory chain
  value advances only after the bytes are on the disk, so a failed write leaves a
  gap that still verifies rather than a break that reads as an edit.
- **`AUTHENTICATION_SUCCEEDED` is recorded after the Session is opened**, not
  after the password checks out. Every other entry records a refusal, which is
  over by the time it is written; an admission is not over until there is a
  Session.
- **No migration, no store column.** Nothing about the record lives in the
  CredentialStore, deliberately: recording must not fail an operation, and a
  store that cannot be written must not become a record that cannot be written.

## 4. What the review found and what was done

**Standards axis** (sub-agent, completed). Verdict: conforms, no hard violations,
no line over 100 characters, no ADR contradicted. Acted on all of it:

- **Ubiquitous-language drift.** Three places still said "audit log" where the
  glossary now says `AuthenticationEventExport`: ADR-0011's final bullet named
  `ExportAuditLog`, *a type that does not exist* — the real error of the batch;
  the handler was `exportAuditLog(ExportAuthenticationEvents …)` where every
  other handler is named for its request; and the Seam 1 test was `AuditLogTest`.
  All three renamed. The wire types themselves had already been renamed from
  `ExportAuditLog`/`AuditLogExported` mid-build for the same reason.
- **Duplicated Code** — the rotation generations were walked three times, and
  `lastChainValueOnDisk` reimplemented the ordering `oldestFirst()` already owns.
  It now walks `oldestFirst()` backwards; two of the three walks remain, and they
  are the rotation and the export, which move in opposite directions on purpose.
- **Mysterious Name** — one concept had three names (`previous`,
  `previousValue`, `previousChainValue()`), `firstKept` read as a noun, and the
  new service field `directory` was vague beside `store`/`events`/`archive`. Now
  `chainValueOfTheLastEntry`, `followed`, `nothingCheckedYet`, `ownDirectory`.
- **A test that asserted nothing** — `recordingSwallowsAFileItCannotWrite`
  passed if `record` did not throw. It now also asserts the entry landed nowhere,
  which is the difference between swallowed and silently written elsewhere.
- **`EVENT_LOG_KEY` had no javadoc** where every constant beside it does. Written.
- **The `@throws` contract** — callers depend on `FileAlreadyExistsException`
  specifically (it becomes `EXPORT_DESTINATION_REFUSED`), and that was documented
  only on `OwnerOnlyFiles.createNew`. Now on `AuthenticationEventArchive.exportTo`
  as well, with the reason it is named apart.
- **Divergent Change on `FileAuthenticationEventLog`** — it now owns writing,
  forcing, rotation, chain bootstrap and both halves of the line format. Noted
  and **not** acted on: ADR-0011 sanctions replacing the inside of this class, the
  line format is the piece that could follow `EventChain` out, and splitting it
  now would be a second seam with one caller. Left for a final reviewer.
- **Primitive Obsession on the chain value** — declined, see §5.

**Spec axis** (this context, not a sub-agent). Every acceptance criterion checked
one by one; §2's table is its verdict. It found no missing criterion and one
piece of scope worth naming: nothing in the ticket asked for control characters
in a subject to be folded, and the folding is there because line-by-line
verification is what makes the chain checkable at all. It is written where it is
done and stated in ADR-0011.

## 5. Open ground — judge these rather than assume them

- **The Spec axis was self-assessed.** §2's table was filled in by the agent that
  wrote the code, because the sub-agent for it died on a session limit. Every row
  names a test, so it can be checked cheaply — but it was not checked by anyone
  else. Start here.
- **Truncating the tail of the record is undetectable, by design.** The chain
  head is held in memory and re-read from the disk, with no sidecar file
  recording where the record had got to. Removing the newest entries therefore
  leaves a sound chain. Both ADR-0001 and ADR-0005 promise a record that "cannot
  be edited or removed, **only withheld**", and losing the newest entries is what
  withholding is — it is already what a failed write does. A reviewer who wants
  the tail protected should argue with this paragraph and with ADR-0011's first
  rejected option, which prices the sidecar.
- **The oldest entry still kept can have its contents edited undetected.** What
  it followed was rotated away, so it is taken on trust and its own chain value
  is what the next entry is checked against. Editing its *chain value* still
  breaks everything after it. This is stated in the export's own comment.
- **The chain stops nobody who holds root.** The key sits at `0600` beside the
  record, so it stops the Administrator — an Account of this system, which is
  exactly the attacker ADR-0001 and ADR-0005 name — and a MachineAdministrator
  reads it and recomputes the file. Reading this as protection against the
  machine's owner would be reading it as a strength no offline product has.
- **The chain value stayed a bare `String`.** The Standards axis called
  Primitive Obsession: `EventChain` got a type and its value did not, and `""`
  ("nothing to follow") and `null` ("not looked for yet") share one field.
  Declined, and worth disagreeing with: the value never crosses a boundary —
  it is computed, written and compared inside two classes in one package — so a
  type would buy no protection anywhere, unlike `SessionToken`, which crosses the
  wire and outlives a request. What was fixed instead is the naming and a javadoc
  that pins both absent states.
- **The `Administrator` can export, but has nowhere to do it from.**
  `ExportAuthenticationEvents` is complete and tested at Seam 1 and unreachable
  from the shipped client: `ServiceLoginGate.admit` asks to act as an `Operator`
  and the service refuses the `Administrator` there by design. This is the *same*
  open question issues #6, #7 and #8 recorded, and it resolves the same way — the
  administration panel is issue #12. **Flagged rather than smuggled in.**
- **The export has no Seam 2 test.** It is covered at Seam 1 and by a codec
  round-trip, and not over a real socket, because no client sends it yet. The
  Lockout ticket earned its socket test by having a screen that meets a Lockout.
- **"Flushed to disk" is asserted as "readable when `record` returns".** A suite
  cannot observe an `fsync`. `FileChannel.force(true)` is the mechanism and the
  test pins the property one layer above it. A reviewer who wants more would need
  to cut power to a machine.
- **A refused first run is not recorded.** Someone who is not a
  MachineAdministrator attempting to create the Administrator leaves no entry,
  because the only thing to record it against is a string that was typed, and
  story 77 keeps typed strings out of the record. ADR-0011 lists this as the
  omission most worth arguing with.
- **An ordinary Session ending is still not recorded**, and this ticket did not
  change that. `SessionExpiryTest.anOrdinaryTimeoutIsNotRecorded` had asserted
  the record was *empty*, which stopped being true the moment authentication
  attempts were recorded; it now asserts the record is unchanged across the
  timeout, which is the claim it was always making.
- **The rotation bounds are constants — a megabyte, five files.** Nothing
  configures them, because nothing in this build would, and a setting nobody
  writes is a constant with a lookup in front of it. The reasoning is the same
  one the `LockoutPolicy` used in the other direction, where a deployment does
  configure it.
- **Every authentication now costs a forced write.** Both branches of the
  equal-cost path pay it — an attempt against a name nobody holds records an
  entry exactly as one against a real Account does — so ADR-0010's symmetry
  holds, and `AbsentAccountCostsTheSameTest` still passes at a quarter-median
  tolerance. Worth a reviewer's eye anyway: it is a new syscall on the hot path
  of the one operation this project times.
- **`ServiceLoginGate.refusalOf` grew two `ErrorCode`s it throws on.** The two
  export codes cannot reach the first run, which carries no Session at all. The
  compiler forced the edit, because the switch is exhaustive — and a clean build
  is what caught it, since incremental compilation had been passing a stale
  `login-ui` for several runs. Worth remembering for the next ticket that touches
  a sealed type: `mvn -o clean test` before believing a green suite.

---

# Code review — Enrolment (issue #10, Seams 1 and 3)

Written for a final reviewing agent. It records what was built, what a two-axis
review found, what was acted on, and — more usefully — what was **not**, so the
next reviewer spends its effort on open ground rather than re-deriving settled
ground.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Base / fixed point | `7025c79`, the tip after issue #9's review record |
| Diff to review | `git diff 7025c79...HEAD` |
| Packages | `com.javafxlogin.core.account`, `…core.authentication`, `…core.ipc`, `…core.policy`, `…core.store`, `com.javafxlogin.ui.login` |
| Build | `mvn -o clean test` → 409 core tests, 66 UI, 1 feature, 0 failures, 1 skipped by an OS guard |
| New decision | ADR-0012 (`docs/adr/0012-the-administrator-never-chooses-a-password.md`) |
| New migration | V005 (`db/migration/V005__enrolment.sql`) — **rebuilds the `accounts` table** |

**How this review was run.** Both axes ran as parallel sub-agents and both
finished — the first ticket in three where that happened, after #8 and #9 each
lost one to a session limit. Neither report is a self-assessment. Both are
summarised in §4 and neither is reproduced whole.

**Scope was settled before any code was written.** Issue #10's eleven acceptance
criteria are all service-side, and the person-facing half of the flow had no home
otherwise, so this ticket delivers the service **and the Operator's enrolment
screen**. The `Administrator`'s side — a screen to create an Account, a screen to
initiate a reset — is left to issue #12, exactly as clearing a `Lockout` and
exporting the record already are.

## 2. What the ticket asked for

Issue #10, "Enrolment: the Administrator never chooses anyone's password" —
parent spec issue #1, stories 18–31, and ASVS 5.0 §6.4.6. It was blocked by #4
(password policy) and #6 (first-run wizard), both landed.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| `CreateAccount` takes no password, returns a 128-bit secret a human can transcribe | met | `CreateAccount` has token/name/Role and no password field; `EnrolmentSecretTest.carriesAHundredAndTwentyEightBits` counts the eight admissible last characters (32²⁵ × 8 = 2¹²⁸); `EnrolmentTest.creatingAnAccountAnswersWithASecretAndTheMomentItRunsOut` |
| Returned **once**, never re-readable | met | no request reads it back; `theSecretIsNeverReadableAgain` searches every byte the service wrote in its own directory |
| Stored hashed, never in the clear, never in the audit log | met | `theSecretIsStoredAsAHashAndNotAsItself`, `theRecordSaysAnEnrolmentWasIssuedAndNotWhatItWas` |
| A fast hash rather than Argon2id, justified by 128 service-generated bits | met | `EnrolmentSecretTest.hashesTheBitsWithSha256AndNothingElse` pins two SHA-256 vectors computed outside this build; the reasoning is ADR-0012 |
| Expires after a configurable lifetime; consumed on first successful use | met | `aSecretThatHasRunOutIsRefused`, `aSecretIsGoodUntilTheMomentItRunsOut`, `howLongASecretLastsIsWhateverTheStoreSays`, `aSecretNeverOutlastsTheTimeItWasIssuedFor`, `aSecretIsConsumedByTheEnrolmentItCompletes`, `aRefusedPasswordLeavesTheSecretWhereItWas` |
| `CompleteEnrolment` carries name, secret and password and **no `SessionToken`** | met | the record has no token component; `completingAnEnrolmentCarriesNoSession` |
| A distinct `ENROLMENT_REQUIRED` refusal at authentication | met | `anAccountAwaitingEnrolmentIsRefusedWithAReasonOfItsOwn`; `AbsentAccountCostsTheSameTest.anAttemptAgainstAnAccountAwaitingEnrolmentCostsTheSameToo` proves it costs what an absent Account costs |
| `InitiateReset` invalidates the old password **immediately** | met | one UPDATE nulls `password_hash` as it writes the secret, and V005's `CHECK` makes both-at-once unrepresentable; `aResetTakesTheOldPasswordAwayImmediately`, `aResetLeavesNoHashOfTheOldPasswordBehind` |
| The Operator is told at the next login that their password was reset, and when | met | `Granted.passwordResetAt`; `theOperatorIsToldAtTheirNextLoginThatTheirPasswordWasReset`, `theOperatorIsToldOnce`, `anOrdinaryLoginIsToldNothing`, `aFirstEnrolmentIsNotAResetAnybodyIsToldAbout`; shown by `SessionWindowTest.anOperatorIsToldTheirPasswordWasResetAndWhen` |
| The Administrator can re-issue a lost or expired secret | met | `InitiateReset` again; `theAdministratorCanReissueASecretThatRanOut`, `reissuingASecretRetiresTheOneBeforeIt` |
| The Administrator's own password stays self-chosen and outside this flow | met | `theAdministratorIsNeverEnrolledByAnybody`, `theAdministratorsOwnPasswordCannotBeResetFromASession` |

## 3. Design decisions a reviewer should judge, not rediscover

- **V005 rewrites the `accounts` table rather than extending it.** `password_hash`
  has been `NOT NULL` since V001, and an Account awaiting enrolment has no
  password — not an empty one, not a placeholder, and above all not a hash of
  something the Administrator picked. SQLite cannot drop a `NOT NULL`, so the
  table is written again and the rows carried across by name.
  `CredentialStoreSchemaTest.anAccountFromAnEarlierSchemaKeepsItsPasswordThroughTheRebuild`
  runs the whole path from a store at V001.
- **A `CHECK` says an Account has a password *or* an outstanding enrolment, never
  both and never neither.** Both would be an Account whose old password still
  works while a secret to replace it is in the post; neither would be an Account
  nobody can use and no Administrator can rescue. In the schema rather than only
  in Java, for the reason the single-Administrator index is.
- **Re-issuing and resetting are one request.** `InitiateReset` puts the Account
  into awaiting-enrolment with a fresh secret; whether there was a password to
  take away decides one thing only — whether the Operator is told about it.
- **The refusal is decided after the Argon2id verification**, exactly as
  ADR-0010 decided for a `Lockout`. A refusal that came back in no time at all
  would name the Account with a stopwatch before the message named it in words.
- **A wrong secret counts towards the `Lockout`; being sent to the enrolment
  screen does not.** The enrolment screen is the one place a credential for such
  an Account can be offered, so leaving it uncounted would make awaiting
  enrolment the single state in which guessing here is free. Counting the
  *routing* refusal, on the other hand, would let whoever guessed a new
  Operator's name lock them out of their own enrolment. ADR-0012 argues both.
- **The secret survives a password the policy refused.** It is consumed by an
  enrolment that completed, not by an attempt at one, or somebody who chose a
  password one character short would need another secret.
- **The lifetime is configuration read on every decision**, so an Administrator
  who shortens it shortens the secrets already in somebody's pocket. The store
  keeps the moment the secret was *issued*, never the moment it expires.
- **Crockford's base 32.** The four characters read as each other by hand are not
  in the alphabet, and three of them are read back as what was meant. 26
  characters is 130 bits, so the last one carries three bits of secret and two of
  nothing — and a text that puts anything in those two is refused, because a
  secret with two spellings cannot be compared by its text.

## 4. What the two-axis review found and what was done

**Standards axis — one hard finding, fixed.** `Enrolments.Issued` was a bare
record holding the plaintext secret as a `String`, so its default `toString()`
printed the one value this whole ticket exists to make unrepeatable, while every
sibling redacts. Fixed structurally rather than by adding a `toString`: the record
now carries the `EnrolmentSecret` itself, which redacts, and
`AuthenticationService.issued` is the single place it becomes text — on its way
out of the process to the screen it is read off. That also closes the Primitive
Obsession the same reviewer flagged.

Also acted on:

- **Duplicated Code.** `waitOf(Duration)` and the `LOCKED_OUT` sentence were
  byte-identical in `LoginController` and the new `EnrolmentController`.
  Extracted to `LockoutText`, following `PolicyViolationText` and
  `SessionEndedText`. The two screens now say the same thing about the same
  Lockout because they read it from the same place.
- **Repeated Switches.** `LoginController` switched on `DeniedReason` twice — a
  `when` guard, then an exhaustive switch that had to `throw` for the case the
  guard had already taken. Now one switch in `refused(NotAdmitted)`, no
  unreachable throw.
- **Hand-built SQL.** `CredentialStore.awaitEnrolment` concatenated a fragment
  and counted bind parameters by hand. Now one fixed statement with
  `password_reset_at = COALESCE(?, password_reset_at)`.
- A dead `fx:id="recoveryWarning"` in `enrolment-window.fxml`, removed.

**Spec axis — eleven of eleven met, one under-proved, fixed.** The fast-hash
criterion rested on Javadoc, ADR-0012 and an assertion that the stored value was
64 hexadecimal characters — which pins a shape and not an algorithm.
`hashesTheBitsWithSha256AndNothingElse` now pins two vectors computed outside
this build, so a later change that salted it, slowed it, or hashed the text
instead of the bits fails.

**Not acted on, deliberately.** The Spec axis named four things as unasked-for.
Three are judgements this ticket owns and stands by, and the fourth is not this
ticket's:

- **The "tengo un código" button on the login screen.** Without it, somebody
  handed a code has to type a password they do not have, be refused, and be sent
  to the screen they were always going to — which works and reads as an
  application that does not know what it wants. Story 23 puts this flow at the
  login screen.
- **The repeated-password field.** Not policy, and nothing is sent when the two
  differ: it is the one rule the screen owns. There is no recovery key here, so a
  typo in a password nobody has ever seen costs another trip to the
  Administrator.
- **`CredentialStore.lockoutPolicy` also catching `DateTimeParseException`.** A
  latent bug in issue #9's code, found by writing the same method for the
  enrolment lifetime: `Duration.parse` throws something that is not an
  `IllegalArgumentException`, so `lockout.lasts_for = 'a while'` escaped past the
  documented `CredentialStoreException` contract. One line, one test
  (`aLockoutLengthThatIsNotOneIsNotGuessedAtEither`), disclosed here rather than
  smuggled.
- **`CLAUDE.md`'s new "Additional behaviour" section** is the repository owner's
  edit, not this ticket's, and was left alone. Both axes flagged it; the Standards
  axis also noted four spelling errors in it.

## 5. Open ground — judge these rather than assume them

- ~~**The reset notice is spent at the moment it is granted, not when it is
  read.**~~ **Closed by the follow-up below.** The Spec axis raised it, it was
  left standing here as the most arguable decision in the change, and the
  repository owner then asked for it to be fixed. See "Enrolment, follow-up" at
  the end of this file: the notice now survives until an
  `AcknowledgePasswordReset` says somebody read it.
- **`ENROLMENT_REQUIRED` is the second refusal that says something about an
  Account.** It names a name as real and as unclaimed. ADR-0012 states the price;
  story 30 asks for it; a reviewer who disagrees should argue with the ADR rather
  than with the code.
- **An Account awaiting enrolment reads as the weakest `PasswordStrength` band.**
  That is V002's rule (an unknown password must not display as a strong one) and
  not a measurement, and the administration panel will be the first thing to show
  it to anybody.
- **Nothing rewraps the `DataKey` on enrolment.** Story 61 belongs to the
  SecretVault (#11), where the `DataKey` first exists. The seam it will need is
  `Enrolments.completedBy` — the single place this build writes a password an
  Operator chose.
- **No language is set on a created Account.** Issue #10's prose mentions a
  language alongside the name and Role; none of its acceptance criteria do, and
  story 104 is issue #13's. `CreateAccount` carries a name and a Role only.
- **The Administrator's half has no screen**, so nothing in the shipped
  application issues a secret yet. Every test that needs one goes through the
  service directly. That is #12.
- **`EnrolmentSecret.text()` returns a `String`.** Unlike a password, it is drawn
  on a screen and read aloud, so every layer between the record and the label
  already holds a copy; what protects it is being consumable once and hashed at
  rest. A reviewer who wants `char[]` here should say what it would buy.
- **The enrolment screen shows the code in a plain `TextField`, not a
  `PasswordField`.** It is being copied character by character off something
  else, and hiding it is how a transcription error becomes three failed attempts
  and a Lockout. `EnrolmentWindowTest.theCodeIsShownAsItIsTyped` pins it, so a
  later change has to argue with the test.

---

# Code review — Enrolment, follow-up (issue #10): the notice is read, not merely sent

An addition to the entry above rather than a replacement for it. The two-axis
review of issue #10 left one thing standing as open ground — §5's first bullet —
and the repository owner asked for it to be closed rather than lived with. This
records what changed and why the first version was wrong.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Base / fixed point | `dc8d1c1`, the enrolment commit above |
| Packages | `com.javafxlogin.core.ipc`, `…core.authentication`, `com.javafxlogin.ui.login` |
| Build | `mvn -o clean test` → 413 core tests, 68 UI, 1 feature, 0 failures, 1 skipped by an OS guard |
| Decision | ADR-0012, amended in place under "**Amended: the notice is spent when it is read, not when it is sent**" |
| New migration | none — the column and its meaning are unchanged; only what ends it moved |

## 2. What was wrong

`Enrolments.resetToDeclareFor` read `password_reset_at` and cleared it in the same
call, on the admission that reported it. That treats **sending as receiving**. A
client that died between being granted a Session and painting a window — a crash,
a kill, a display that never came up — had already spent the only copy, and the
Operator would never be told that an Administrator had taken their password away.
It is the one message this service produces whose whole purpose is to reach one
particular person, and it was the one being dropped silently.

The service, rather than the person, was deciding they had been told.

## 3. What it does now

- The notice rides on **every** admission while `password_reset_at` is set.
- A new request, `AcknowledgePasswordReset`, ends it. It carries the
  `SessionToken` and nothing else: the Account it clears is the Session's own, so
  a patched client cannot dismiss somebody else's notice, and only somebody who
  has proved they hold the Account can say they were told about it.
- Reading a notice **is not activity**. It goes through the same
  `onTheSessionNamedBy` path every other Session request uses, which never touches
  the countdown, so acknowledging one does not keep alive the Session of an
  Operator who walked away from the screen it was on.
- Acknowledging nothing answers `Ok`. What the caller asked for is that the notice
  be over, and afterwards it is; a client that sends it twice has done nothing
  wrong.
- The window shows an "Entendido" button beside the notice, unmanaged while there
  is nothing to say. It **dismisses first and tells the service afterwards**,
  which is the safe direction: a report that never arrives costs one repeat of a
  sentence, and the repeat is the mechanism working rather than failing.

## 4. What it costs, stated plainly

A person who never presses the button is told again at every login, forever.
That is the intended failure and it is the right way round: being told twice is
cheaper than being told never, about a password somebody else took away.

## 5. Evidence

| Claim | Proof |
|---|---|
| Said again on every admission until read | `EnrolmentTest.theOperatorIsToldAgainUntilTheySayTheyHaveReadIt` — three logins, three notices |
| Reading it is the only thing that ends it | `theOperatorIsToldNoMoreOnceTheyHaveReadIt` |
| Acknowledging is not activity | `sayingTheNoticeWasReadIsNotActivity` — 10 minutes, an acknowledgement, 6 more, and the Session has expired |
| Idempotent, and fine when there is nothing to acknowledge | `sayingItWasReadWhenThereWasNothingToReadIsStillOk` |
| A token naming no Session dismisses nothing | `aTokenThatNamesNoSessionAcknowledgesNothing` — and asserts the notice is still owed afterwards |
| Carried on the wire | `MessageCodecTest.carriesEverySessionRequestsTokenByteForByte` |
| The window dismisses it and tells the service | `SessionWindowTest.theNoticeIsOverOnlyWhenThePersonSaysTheyHaveReadIt` |
| A service that cannot be told does not undo the dismissal | `SessionWindowTest.aNoticeStaysDismissedEvenWhenTheServiceCannotBeTold` |
| Nothing to say means nothing on the window | `SessionWindowTest.anOrdinaryLoginIsToldNothing` |

## 6. Open ground this leaves

- **`FakeLoginGate` grew a second way to fail.** `cannotBeToldTheNoticeWasRead`
  exists because `becomeUnreachable` is too broad for this test: a service that
  has gone away entirely also ends the Session the `SessionGuard` is watching,
  which closes the very window the assertion is about. A reviewer should check
  that the narrower switch is not hiding something the broad one would catch.
- **The button is the only way to dismiss it.** Closing the window, logging out,
  or letting the Session expire all leave the notice owed — deliberately, since
  none of those is evidence anybody read it, but it does mean an Operator who
  habitually ignores the bar sees it every morning.
- **Nothing records that the notice was read.** It is not an
  `AuthenticationEvent`: the record already holds `PASSWORD_RESET_INITIATED`
  permanently, and what somebody clicked at their own screen is not a fact about
  access. Arguable — a deployment auditing whether resets are being noticed would
  want it.

**The clean-build warning at the end of issue #9's entry earned its place again.**
`MessageCodecTest.tokenOfRoundTripped` switches over `Request` with a `default`
that throws, so adding a token-carrying request compiled and passed incrementally
and failed under `mvn -o clean test`. The lesson stands: a green incremental suite
is not evidence when a sealed type has grown.

---

# Code review — SecretVault (issue #11, Seams 1–3)

Branch `dev-login`. Stories 55 to 63, ADR-0004 (two stores and cryptographic
unlock), ADR-0005 (the Administrator's exclusion is least privilege), ADR-0006
(no machine binding).

## 1. Where the code is

| Concern | File |
|---|---|
| The Vault itself | `login-core/.../vault/SecretVault.java` |
| The Vault while a Session holds it | `login-core/.../vault/UnlockedVault.java` |
| The DataKey, unnameable from outside | `login-core/.../vault/DataKey.java` |
| The key a password derives | `login-core/.../vault/KeyEncryptionKey.java` |
| The machine's copy's key | `login-core/.../vault/MachineKey.java` |
| AES-256-GCM, HKDF-Expand, UTF-8 without a String | `vault/AesGcm.java`, `vault/Hkdf.java`, `vault/Utf8.java` |
| The Vault's schema | `login-core/src/main/resources/db/vault/V001__secret_vault.sql` |
| Migration machinery, now shared by two files | `login-core/.../store/NumberedMigrations.java` |
| Unlocking, refusing, wrapping, rewrapping, destroying | `login-core/.../authentication/AuthenticationService.java` |
| The key's lifetime tied to the Session's | `login-core/.../authentication/Sessions.java` |
| New messages | `ipc/ReadSecret`, `ipc/KeepSecret`, `ipc/SecretRevealed`, `ipc/ChangeOwnPassword`, `ipc/DeleteAccount` |
| What a host product calls | `login-ui/.../LoginGate.java`, `SecretOutcome`, `SecretGiven`, `SecretKept`, `SecretWithheld` |
| Tests | `vault/SecretVaultTest` (19), `authentication/SecretVaultAccessTest` (21), additions to `MessageCodecTest`, `StoreFilePermissionsTest`, `ServiceLoginGateTest` |

## 2. What the ticket asked for

### Acceptance criteria against evidence

| Criterion | Where it is met | Proof |
|---|---|---|
| A ProtectedFeature requests a named secret and receives it | `LoginGate.secretNamed` → `ReadSecret` → `UnlockedVault.secretNamed` | `ServiceLoginGateTest.aProtectedFeatureKeepsASecretAndAsksForItByName` (real socket, real service), `SecretVaultAccessTest.aProtectedFeatureAsksForANamedSecretAndReceivesIt` |
| Decrypted one at a time at the moment of use | `UnlockedVault.secretNamed` derives a key per name and opens one row | `SecretVaultTest.unlockingDecryptsNoSecretAtAll` — a ciphertext nothing opens sits beside a good one, and the unlock does not notice |
| The raw DataKey is never exposed through the API | `DataKey` is package-private; no public method returns `byte[]` | `SecretVaultTest.nothingPublicHandsOutKeyMaterial` — a test about types, so a later convenient getter has to argue with it |
| Enrolment wraps under a KEK from the chosen password, salt and parameters separate from the auth hash | `AuthenticationService.completeEnrolment` → `SecretVault.wrapFor`; `KeyEncryptionKey` reads neither the PHC string nor its salt | `SecretVaultAccessTest.theVaultsSaltIsNotTheOneInsideTheAuthenticationHash` compares the two files' salts; `completingAnEnrolmentIsWhatGivesAnOperatorAWrappedCopy`, `SecretVaultTest.wrappingAgainReplacesTheWrapAndSaltsItAfresh` |
| `ChangeOwnPassword` rewraps | `UnlockedVault.rewrapUnder`, through the key the Session already holds | `changingAPasswordRewrapsRatherThanLosingTheSecrets` at both seams |
| Deleting an Operator destroys their wrapped copy | `deleteFor` destroys the wrap **before** the row | `SecretVaultAccessTest.deletingAnOperatorDestroysTheirWrappedCopy` |
| Every Vault operation from an Administrator Session refused by the service | `onlyAnOperator`, which also records the attempt | `everyVaultOperationFromAnAdministratorIsRefusedByTheService`, `theRefusalIsWrittenToTheRecord` |
| Nothing claims secrets are protected *from* the Administrator | `CONTEXT.md` corrected, `README.md` says it plainly, `ServiceLoginGate`'s javadoc rewritten | `SecretVaultAccessTest.anAdministratorReachesTheVaultByCreatingAnOperator` — the detour is asserted to **work**, and to leave two events |
| A separate file from the CredentialStore, owned by the service | `secrets.db` and `secrets.key` beside the store, both owner-only | `theVaultIsItsOwnFileBesideTheCredentialStore`, `StoreFilePermissionsTest.theSecretVaultAndItsMachineKeyAreCreatedOwnerOnly` |

**The claim in `CONTEXT.md` was false and is now fixed.** The glossary said "An
Administrator can never read secrets held by the Vault", which contradicts
ADR-0005 and criterion 8. It now says what is true: no Vault access, and no way to
obtain it without leaving a record.

## 3. Design decisions a reviewer should judge, not rediscover

- **The unlock has no boolean in it.** `SecretVault.unlockFor(name, password)`
  derives Argon2id over the wrap's own salt and parameters and tries to open an
  AES-GCM ciphertext. A wrong password fails the tag. There is nothing here for a
  patched build to skip, and
  `SecretVaultAccessTest.aSessionGrantedWithoutTheRealPasswordOpensNoVault` proves
  it the hard way: the stored hash is replaced so that a different password
  authenticates, the service answers `Granted`, and the Vault stays shut.
- **The key's lifetime is the Session's, enforced in `Sessions`.** All four ways a
  Session ends already funnel through that class, so the `UnlockedVault` is closed
  there rather than in the service. A DataKey outliving its Session is the one bug
  in this design nobody at the keyboard could see, so it is made structural.
- **`SessionOutcome.Live` carries the Vault.** A request reaches a secret by
  presenting a token the service granted *and* finding the key that token's
  password unwrapped. Putting it in the outcome rather than in a lookup by name is
  ADR-0004's arrangement expressed in one field.
- **Login now costs two Argon2id derivations for an Operator.** One verifies, one
  derives the KEK; that is the price of the two being cryptographically separate.
  Only a *successful* authentication pays it, so the equality between a wrong
  password and a name nobody holds — one derivation each — is untouched.
- **A per-secret key, not the DataKey.** `Hkdf.expand(dataKey, name, 32)`. Expand
  and not extract, because the DataKey is already uniform (RFC 5869 §3.3). It buys
  name binding: a row moved between names fails its tag.
- **The DataKey is made when the file is made**, not at the first enrolment, so
  every later operation has one shape and there is no first Operator who is
  special.
- **A reset destroys the wrap.** The old password stops opening the Vault at the
  moment it stops authenticating, which is ASVS 5.0 §6.4.6 applied to the Vault.
  Enrolment writes a new wrap from the machine's copy, so nothing is lost.
- **Delete destroys the wrap first, then the row.** The other order leaves Vault
  access reachable again by creating an Account with the same name.
- **A wrong current password at `ChangeOwnPassword` is counted like any other
  failure**, on the enrolment screen's argument: leaving it uncounted would make
  this the one place where guessing is free. The cost is that a mistyped old
  password can lock somebody out of their next login.
- **Reading a secret is not activity.** It goes through the same
  `onTheSessionNamedBy` path as every other question, so a ProtectedFeature that
  polls for a credential cannot keep alive the Session of somebody who walked away.
  Pinned by `readingASecretIsNotActivity`.
- **Secret reads are not audited.** Story 73 lists what the record holds —
  authentication attempts, Lockouts, Account changes, configuration changes,
  exports — and a working Operator's reads are none of those. What is recorded is
  `VAULT_REFUSED_TO_AN_ADMINISTRATOR`, `PASSWORD_CHANGED` and `ACCOUNT_DELETED`.
  Arguable; see §5.
- **`NumberedMigrations` was extracted rather than copied.** The Vault needed the
  same numbered-migration machinery; `SchemaMigrations` keeps its list and its
  static API and delegates. `SchemaTooNewException` now names which file it is
  about, because there are two.

## 4. Scope taken deliberately, and why

- **`KeepSecret` is not in the ticket.** No story asks for a write path, and
  without one the Vault cannot be populated by anything but a test's back door —
  criterion 1 would be unreachable in a real deployment. It is an Operator's
  request and the Administrator is refused on both sides, so it costs ADR-0005
  nothing.
- **A reset destroys the wrap.** Not among the nine criteria, and named here
  because it is a Vault write made on an Administrator's behalf. It is what makes
  the reset honest — the old password stops opening the Vault at the moment it
  stops authenticating — and it is ADR-0004's "rewrap after a password reset" half
  built. It gives the Administrator nothing: destroying a wrap cannot read one.
- **`ChangeOwnPassword` and `DeleteAccount` exist at the service and have no
  screen.** Criteria 5 and 6 name both, and the windows that reach them are issue
  #12's. Same position as clearing a Lockout and exporting the record.
- **The Administrator branch of the gate's error mapping is not covered at seam
  3.** `LoginGate.admit` asks to act as an Operator, so this client cannot obtain
  an Administrator Session at all; the refusal is asserted at seam 1 instead. The
  mapping exists for a patched client, which is the only thing that can reach it.

## 5. Open ground — judge these rather than assume them

- **Nothing records that a secret was read.** A deployment auditing which
  credentials are pulled and when would want it, and the argument against is
  story 73's list plus the record being bounded at a megabyte. A reviewer may
  reasonably decide the other way.
- **`char[]` in, `String` on the wire.** `SecretRevealed` carries an array, and
  `MessageCodec` turns it into a JSON string on the way out — the same copy
  ADR-0003 already accepts for passwords. The array is worth having at the ends,
  and worth nothing in the middle.
- **A secret's plaintext is not overwritten by the gate.** `SecretGiven` hands the
  array to the host product and says so; how long the product keeps it is the one
  part of this the gate cannot decide.
- **`DataKey.isDestroyed()` reads "all zeroes".** A key that was legitimately all
  zeroes would be misread, at probability 2⁻²⁵⁶. Stated rather than defended.
- **The MachineKey does not rotate, by design**, and a key file that is replaced
  makes provisioning impossible for ever after — asserted in
  `aVaultWhoseMachineKeyHasBeenReplacedRefusesToProvision`. Existing Operators keep
  working, which is the right failure, but nothing warns anybody.
- **The Vault is not in the backup**, per ADR-0006 and story 84 — and issue #14 has
  not landed, so there is nothing yet to exclude it from.
- **`TransportTest.refusesAnOversizedDeclarationWithoutWaitingForTheBodyBehindIt`
  failed once during this work and passed on every rerun**, including twice in
  isolation. It touches nothing this ticket changed (a socket race in the
  transport's own test), but it is flaky and somebody should look at it.

## 6. What the two-axis review found and what was done

Both axes ran against the staged diff, against `HEAD` as the fixed point.

### Acted on

| Axis | Finding | What was done |
|---|---|---|
| Spec | **The MachineKey's mode was set at creation and never reasserted**, unlike the store and the Vault — on the one file that unwraps the DataKey with no password at all, and whose own javadoc says the mode is all that protects it. A stray `chmod` survived every restart. | `MachineKey.readOrCreate` now reasserts owner-only on an existing file. `StoreFilePermissionsTest.thePermissionsOfTheMachineKeyAreReassertedWhenItIsReopened` |
| Spec | **`ChangeOwnPassword`'s ordering claim was false.** The comment argued that wrapping first avoids "an Operator who can log in and read nothing", and the order produced exactly that: a store failure after the rewrap left the old hash beside a wrap under the new password. | `rewrapAndRecord` writes the store first and **puts the old hash back** if the Vault refuses the rewrap, which is as close to a transaction across two files as this gets. The comment now says what happens instead of what was hoped. |
| Spec | **Criterion 2 was claimed, not asserted.** The cited tests proved per-name key derivation, not that unlocking decrypts nothing. | `SecretVaultTest.unlockingDecryptsNoSecretAtAll`: a ciphertext nothing opens sits beside a good one; a build that decrypted at unlock fails at the unlock. |
| Spec | **Criterion 4's separation was structural only** — no test compared the two salts. | `SecretVaultAccessTest.theVaultsSaltIsNotTheOneInsideTheAuthenticationHash` reads the PHC salt out of `accounts` and the `kdf_salt` out of `data_key_wraps`. |
| Standards | **Duplicated Code** — `changePasswordFor` re-implemented `authenticate`'s verify sequence, carried a dead `verifyAgainstAbsentAccount` branch, and checked the Lockout **before** the verification, which is the opposite of what `authenticate` documents at length. | One `verified(account, password)` used by both, and the Lockout read after the verification as it is on the login path. |
| Standards | **Speculative Generality** — one `SecretOutcome` served both operations, so every reader had to handle `SecretKept`. The README's own example showed the wart. | Split into `SecretOutcome` (given \| withheld) and `SecretKeepingOutcome` (kept \| withheld); `SecretWithheld` implements both, because it is the same refusal either way. |
| Standards | **`KeyEncryptionKey` was not in the glossary**, sitting at the same level as `DataKey` and `MachineKey`, which both are. | Added to `CONTEXT.md`, with the `_Avoid_` list `docs/agents/domain.md` asks for. |
| Standards | `DataKey.isDestroyed()` inferred destruction from all-zero bytes with no prose; `VaultException` forced `new VaultException("…", null)`; `String what` was a message fragment. | The heuristic and its 2⁻²⁵⁶ cost are now written down; a cause-less constructor added; renamed to `fileMigrated` / `fileFound`. |

### Deliberately not acted on

- **"Three key wrappers duplicate each other" (Standards).** `DataKey`,
  `KeyEncryptionKey` and `MachineKey` do share a shape. They are three different
  glossary terms with three different lifetimes — one per Vault, one per request,
  one per machine — and merging them into a `KeyMaterial` would trade three names
  the domain uses for one the domain does not. The inconsistency the reviewer spotted
  is real, though: the MachineKey alone is never destroyed, because it is held for
  the service's lifetime by design.
- **"`SchemaMigrations` is now a Middle Man" (Standards).** Every member does
  delegate, but what the class holds is the CredentialStore's migration *list*, and
  that list is the thing worth having a name and a test for.
- **"`KeepSecret` is scope creep" (Spec).** Agreed that no story asks for it, and
  it stays: without a write path the Vault cannot be populated by anything but a
  test's back door, so criterion 1 would be unreachable in a real deployment. See
  §4.
- **"A reset destroying the wrap sits awkwardly beside criterion 7" (Spec).**
  Criterion 7 is about an Administrator *reaching* the Vault. Destroying a wrap
  reads nothing and gives them nothing; it is what stops a reset from leaving the
  old password able to open the Vault. Recorded in §4 as scope taken deliberately.

### Left open

- **`SessionOutcome.Live` is a public record carrying `Optional<UnlockedVault>`**,
  so anything in-process holding a `Live` reaches `keep` and `secretNamed` without
  passing `onlyAnOperator`. Nothing does — the only producer is `Sessions` and the
  only consumer is the service, and every client is on the far side of a socket —
  but narrowing `SessionOutcome` to its package would touch the Session lifecycle
  ticket's code and was left for whoever reviews both.
- **The rollback in `rewrapAndRecord` is untested.** Forcing a `VaultException`
  between two writes needs a seam this service does not have. The path is four
  lines and stated; a reviewer should decide whether that is enough.

---

# Code review — Administration panel (issue #12, Seams 1 and 3)

Written for a final reviewing agent, in the shape the earlier sections use: what
was built, what the ticket asked for against evidence, the decisions worth
judging rather than rediscovering, and what is deliberately left open.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Commits | `4d9d533` (implementation), `8f53875` (review fixes), and this one, which records them |
| Base / fixed point | `9f8f76b` ("Open the Vault with a password rather than with an answer") |
| Diff to review | `git diff 9f8f76b...HEAD` |
| Packages | `com.javafxlogin.core.account`, `…core.ipc`, `…core.store`, `…core.authentication` in `login-core`; `com.javafxlogin.ui.login` in `login-ui` |
| Binding decision | ADR-0013 (`docs/adr/0013-the-account-list-crosses-the-socket-and-nothing-else-does.md`) |
| Build | `mvn -o test` → 578 tests, 0 failures, 1 skipped by an OS guard (core 482, ui 95, feature 1), after the review fixes in §5 |

New at the service: `ListAccounts` / `AccountsListed`, `AccountSummary`,
`CredentialStore.accounts()`, migration `V006__language_preference.sql`.
New at the client: `AdministrationWindow`, `AdministrationController`,
`administration-window.fxml`, `AccountText`, and eight small outcome types
(`AccountListing`, `AccountsSeen`, `AccountProvisioned`, `EnrolmentSecretIssued`,
`AdministrationOutcome`, `Administered`, `ExportOutcome`, `EventsExported`,
`AdministrationRefused` + its reason enum). The `LoginGate` grows `administer`
and seven administration methods; the login screen grows one checkbox.

## 2. What the ticket asked for

Issue #12, "Administration panel: Accounts, configuration and log export".
Blocked by #8 (Lockout) and #10 (enrolment), both landed. Parent spec is issue
#1, stories 18–22, 26–28, 37–39, 47–48, 62, 74–75.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Accounts listed with Role, band, language and Lockout | met | `AccountListingTest` (5 tests, at the service) + `AdministrationWindowTest.everyAccountIsListedWithWhatTheAdministratorNeedsToKnowAboutIt`, `…anAccountWithNoLanguagePreferenceSaysSoRatherThanNamingOne`, `…anAccountThatIsLockedOutIsListedAsLockedOut`, `…anAccountAwaitingEnrolmentIsSaidToBeWaitingRatherThanShownABand` |
| Creating an `Operator` shows the secret once, with a warning | met | `AdministrationWindowTest.creatingAnOperatorShowsTheEnrolmentSecretOnceWithAWarning`, `…theEnrolmentSecretIsGoneOnceTheAdministratorSaysTheyHaveWrittenItDown`, `…nothingAskedOfThePanelAfterwardsBringsTheSecretBack` |
| An `Operator` can be deleted, consequences stated | met | `…deletingAnOperatorStatesWhatItCostsBeforeItHappens` (asserts nothing was deleted before confirming), `…theOperatorIsDeletedOnceItIsConfirmed` |
| A reset without the `Administrator` choosing the password | met | `…aPasswordResetHandsBackASecretAndNeverAsksForAPassword` — asserts the request made **and** that the panel has no `PasswordField` at all |
| A `Lockout` can be cleared | met | `…aLockoutCanBeCleared` |
| Inactivity period changed; expiry disabled | met | `…theInactivityPeriodCanBeChanged`, `…expiryCanBeSwitchedOffEntirely`, `…aPeriodThatIsNotANumberOfMinutesChangesNothingAndSaysSo` |
| The audit log can be exported | met | `…theRecordCanBeExportedAndSaysWhatTheCopyCameTo`, `…anExportWhoseChainDidNotHoldSaysSoInItsOwnWords` |
| `SecondFactor` present, visibly disabled, doing nothing | met | `…theSecondFactorControlIsThereAndDisabled` |
| Panel reachable only by an `Administrator` `Session`, enforced by the service | met | `AccountListingTest.anOperatorIsRefusedTheListOfAccounts`, `…aSessionThatIsOverIsToldSoRatherThanAnsweredWithTheList`; every other request was already enforced (`RoleEnforcementTest`, `InactivityPeriodConfigurationTest`, `LockoutTest`, `EnrolmentTest`) |
| UI tests drive the panel headless on Monocle against a fake `LoginGate` | met | `AdministrationWindowTest` (23 tests, 99 s) — TestFX + Monocle, `FakeLoginGate` |

## 3. Design decisions a reviewer should judge, not rediscover

- **The account list crosses the socket.** This is the only decision here that
  touches the security property, and ADR-0013 is written for a reviewer who
  reaches for ADR-0002 first. Short version: the request needs a `SessionToken`
  the service issued to an Administrator; what crosses is an `AccountSummary`
  with no field a hash could travel in; the query names its columns.
- **`AccountSummary` is a second type rather than a trimmed `Account`.** The
  duplication is deliberate — an `Account` carries the hash, and a build that
  reused it would be one field away from sending one.
- **The `Lockout` is filled in by the service, not the store.** `CredentialStore`
  has no clock; `AuthenticationService.everyAccount()` maps each summary through
  the same `Lockouts` the login screen's refusals go through, so the panel cannot
  come to disagree with the login screen. `AccountSummary.lockedFor(…)` is what
  makes that a copy rather than a mutation.
- **Story 37 is a checkbox.** One login screen; the box decides which `Role` the
  attempt asks for. `LoginGate.administer` is a separate method from `admit` and
  leads to a separate window, because an Administrator never reaches the
  `ProtectedFeature` — the host's view function is not called on that path, which
  `theHostProductsViewIsNeverBuiltForAnAdministrator` asserts.
- **Four sealed outcome sets rather than one.** `AccountListing`,
  `AccountProvisioned`, `AdministrationOutcome`, `ExportOutcome`, all sharing
  `AdministrationRefused`. This follows the ruling in the SecretVault review
  ("Speculative Generality — one `SecretOutcome` served both operations"): no
  caller handles a case another request produces. The cost is eight small files.
- **The panel carries a `SessionGuard`**, like the window an Operator works in.
  An Administrator's Session expires by the same `InactivityPeriod` and the panel
  closes and hands back the same way.
- **Deleting is two clicks with the consequences in between**, in the window
  rather than in a modal dialog — a dialog is not drivable headless on Monocle,
  and this is testable.
- **`V006` adds a nullable `language_preference` column that nothing in this build writes.**
  Criterion 1 asks for the column; issue #13 owns choosing and applying a
  preference and is blocked by this ticket. A reviewer should decide whether the
  column belongs here or with #13; the argument for here is that #12 lists it and
  #13 then only adds the selector.

## 4. Open ground — judge these rather than assume them

- **The panel cannot show the `InactivityPeriod` currently in force.** No request
  answers what the deployment is configured with, so the Administrator types a
  new value blind. This is the one part of the screen that reads as unfinished.
  It is recorded in ADR-0013 rather than hidden here.
- **The export destination is a typed path, not a `FileChooser`.** Drivable
  headless, and the refusals are the service's either way. A chooser is a better
  screen and not a different decision.
- **Nothing exercises the panel against the real service.** Seam 1 tests the
  decisions and Seam 3 tests the window against a fake gate; the wiring between
  them — `ServiceLoginGate`'s seven new methods — is covered only by
  `ServiceLoginGateTest`'s existing shape, which this change does not extend. A
  reviewer should decide whether that seam is worth a test of its own.
- **`AdministrationController` is long** (a screen with seven jobs on it). It was
  left as one class because every job is three lines of "ask the gate, show what
  came back"; splitting it would move the wiring rather than remove it.
- **The `language_preference` column has no `CHECK` and no writer in this
  build.** A tag naming no language is refused when read, in the store and again
  in the codec, rather than read as "said nothing" — which is the distinction #13
  will depend on. Both branches are now tested by writing the column the way #13
  will.
- **Reading the list is not an AuthenticationEvent**, on the argument in
  ADR-0013. If a reviewer disagrees, the change is one line in
  `listAccounts` — and the record would then fill with the panel refreshing
  itself after every change.

## 5. What the two-axis review found and what was done

Both axes ran against `4d9d533`, with `9f8f76b` as the fixed point.

### Acted on

| Axis | Finding | What was done |
|---|---|---|
| Standards | **The glossary's own term was not used.** `CONTEXT.md` defines `LanguagePreference` with `_Avoid_: … language`, and this commit — the one that added the entry — named the concept `language` in the record, the column, the JSON field, the store, `AccountText` and the FXML. | Renamed throughout: `AccountSummary.languagePreference`, `language_preference` in V006, `"languagePreference"` on the wire, `AccountText.preferenceOf`. |
| Spec | **An Account awaiting enrolment was listed as a weak password.** Story 72 asks an Administrator to find the Accounts worth nudging; one that has never had a password is not one of them, and the store's `WEAK` is V002's floor rather than a measurement. | `AccountSummary.passwordStrength` is now `Optional<PasswordStrength>`, empty while awaiting enrolment; the store asks `password_hash IS NULL` (never selecting the hash); the panel says *Pendiente de alta*. `AccountListingTest.anAccountAwaitingEnrolmentIsListedWithNoBandAtAll`, `AdministrationWindowTest.anAccountAwaitingEnrolmentIsSaidToBeWaitingRatherThanShownABand`. |
| Spec | **The panel asserted an event the service does not make**: a reset of an Account with no password said "la contraseña … ha dejado de funcionar", while `AuthenticationService` deliberately records no `PASSWORD_RESET_INITIATED` for one. | The sentence is chosen from the Account's own state, which the panel now knows. `AdministrationWindowTest.reissuingASecretDoesNotClaimAPasswordStoppedWorking`. |
| Spec | **The `language` column's reading half was untested** — only the empty branch was exercised, so neither the tag parsing nor its refusal ran. | `AccountListingTest.anAccountThatHasChosenALanguageIsListedWithIt` and `…aLanguagePreferenceThatNamesNoLanguageIsRefusedRatherThanReadAsSilence` write the column the way issue #13 will. |
| Spec | **"Never built" was asserted as "not on the screen."** | The test now counts calls to the host's `Function<Session, Parent>` and asserts zero. |
| Spec | **Nothing asserted that the secret cannot be asked for again** (criterion 2). | `AdministrationWindowTest.nothingAskedOfThePanelAfterwardsBringsTheSecretBack`. |
| Standards | **Wording belongs in a `*Text` class** here (`SessionEndedText`, `PolicyViolationText`, `LockoutText`), and `sentenceFor(AdministrationRefusedReason)` was inlined in the controller as `case SESSION_OVER -> SESSION_OVER;`. | Extracted `AdministrationRefusedText`, which the export's own client-side refusal now shares. |
| Standards | Duplicated Code — `showTheSecret` / `showTheDelete` were the same visible/managed triple; the repeated `SESSION_OVER` construction in `ServiceLoginGate`; a 151-column javadoc line left by widening `LockoutText.waitOf`. | `show(boolean, Node…)`; `sessionOver()`; the javadoc rewrapped and every line this change introduced brought back inside the width the rest of the tree keeps. |
| Standards | Primitive Obsession / control flag — `LoginController` threaded `boolean administering` through two methods. | The attempt and where it leads are chosen together, once: `Supplier<Admission>` and `Consumer<Admitted>` picked in `onAdmit`. |
| Standards + Spec | Speculative Generality — `AccountSummary.isLockedOut()` was production API used only by a test. | Removed; the test asks `lockedFor().isPresent()`. `isAwaitingEnrolment()` replaced it, and is used by the panel. |
| Spec | The minutes box stayed enabled and unread while *Sin caducidad* was ticked. | `inactivityMinutes.disableProperty()` is bound to the checkbox. |

### Deliberately not acted on

- **"The log-out button was not asked for" (Spec).** True — issue #12 lists no
  logout criterion, and story 49 is an Operator's. It stays: one machine holds
  one Session at a time, so an Administrator with no way to leave holds the
  machine until inactivity expires, and the alternative is closing the window
  with the decoration and hoping.
- **"`ServiceLoginGate` repeats a three-arm switch four times" (Standards).** The
  repeated arms are now two lines each and the shared one is named. Folding the
  rest behind a generic helper would replace four readable switches with one
  signature carrying a mapper per call site.
- **"A new `ErrorCode` forces edits in five places" (Standards, Shotgun
  Surgery).** That is the closed-set design working: the codec, the two client
  translations and the wording are each exhaustive on purpose, so a code added
  and worded nowhere fails to compile rather than reaching somebody as a blank
  refusal.
- **"`AdministrationController` is one class for seven jobs" (Standards,
  Divergent Change).** Still true, still recorded in §4. Every job is three lines
  of "ask the gate, show what came back"; splitting it would move the wiring.
- **"Story 26 (re-issue a lost code) has no button of its own" (Spec).** It is
  the reset button, as the service has it — `InitiateReset` against an Account
  awaiting enrolment simply has no password to take away. What the review was
  right about is that the panel could not *tell* which Accounts those were; it
  now can, and the wording follows.

# Code review — Interface language (issue #13, Seams 1–3)

Written for a final reviewing agent, in the shape the earlier sections use: what
was built, what the ticket asked for against evidence, the decisions worth
judging rather than rediscovering, and what is deliberately left open.

## 1. Where the code is

| | |
|---|---|
| Branch | `dev-login` |
| Commits | this one |
| Base / fixed point | `9e05fc7` ("Name in the report the commits the report is about") |
| Diff to review | `git diff 9e05fc7...HEAD` |
| Packages | `com.javafxlogin.core.ipc`, `…core.store`, `…core.authentication`, `…core.audit` in `login-core`; `com.javafxlogin.ui.login` in `login-ui` |
| Binding decision | ADR-0014 (`docs/adr/0014-the-interface-language-is-the-clients-and-is-chosen-twice.md`) |
| Build | `mvn -o test` → 623 tests, 0 failures, 1 skipped by an OS guard (core 499, ui 123, feature 1) |

New at the service: `ChangeLanguagePreference`, `LANGUAGE_PREFERENCE_CHANGED`,
`CredentialStore.setLanguagePreference` / `languagePreferenceOf`, and an
`Optional<Locale>` on `Granted`. New at the client: `InterfaceLanguage`,
`GateFlow`, `messages.properties`, `messages_es.properties`,
`languages.properties`, and `LoginGate.useLanguagePreference`. Every window is
now loaded with a bundle, so the five FXML files hold `%keys` and not sentences.

## 2. What the ticket asked for

Issue #13, "Interface language: ResourceBundle, OS locale and per-Account
preference". Blocked by #12 (administration panel), landed. Parent spec is issue
#1.

### Acceptance criteria against evidence

| Criterion | Status | Proof |
|---|---|---|
| Every user-facing string comes from a `ResourceBundle`; adding a language touches no code | met | `messages.properties` + `messages_es.properties`; the five FXML files hold only `%keys`; `grep` for a Spanish literal in `login-ui/src/main` returns nothing. Which languages exist is `languages.properties`, so a new language is two files |
| The login screen and the first-run wizard follow the operating-system locale | met | `InterfaceLanguage.ofTheMachine()` reads `Locale.Category.DISPLAY`; `WordingTest.theMachinesOwnLanguageIsTheOneItDisplaysIn`, `…theClosestOfferedLanguageIsTheOneDrawn`, `…aLocaleThatNamesNoLanguageIsDrawnInTheFirstOneOffered`; `LanguageWindowTest.theLoginScreenFollowsTheMachine` |
| A language selector on the login screen overrides that locale for the session | met | `LanguageWindowTest.theSelectorOffersEveryLanguageThisBuildShips`, `…choosingALanguageRedrawsTheLoginScreenInIt`, `…whatTheOldLanguageSaidDoesNotSurviveTheNewOne`, `…theEnrolmentScreenIsDrawnInTheLanguageTheLoginScreenWasIn` |
| After authentication, the Account's own language preference is applied | met | `LanguagePreferenceTest.anAdmissionCarriesTheLanguageTheAccountReads`, `…anAdministratorsOwnAdmissionCarriesTheirLanguage`, `…anAdmissionOfAnAccountThatHasSaidNothingCarriesNoLanguage` (seam 1); `ServiceLoginGateTest.carriesALanguagePreferenceThroughToTheServiceAndBackOnTheNextAdmission` (seam 2); `LanguageWindowTest.theAdmittedAccountsOwnLanguageIsWhatTheirWindowIsDrawnIn`, `…anAccountThatHasSaidNothingKeepsTheLanguageTheLoginScreenWasIn` (seam 3) |
| The `Administrator` can set an Account's language preference from the administration panel | met | `LanguagePreferenceTest` (8 tests: who may, who may not, a name no Account holds, a Session that ended, the record); `AdministrationWindowTest.anAdministratorSaysWhichLanguageAnAccountReads`, `…anAccountIsPutBackToFollowingTheMachine`, `…choosingAnAccountShowsTheLanguageItReads`, `…aLanguageCannotBeSetWithoutChoosingAnAccountFirst` |
| Spanish and English bundles both exist and are complete | met | `WordingTest.everyOfferedLanguageHoldsExactlyTheKeysTheBaseBundleHolds`, `…nothingIsWordedAsNothing`, `…everyMessageTakesTheSameThingsInEveryLanguage` |
| No string is concatenated from fragments in a way that cannot be translated | met | every sentence is one key; the one sentence that changes with a number chooses inside the bundle (`lockout.wait` is a `ChoiceFormat`), asserted by `WordingTest.aWaitIsSaidWithItsNumberInEveryLanguage`. What is still joined is whole sentences: `PolicyViolationText.paragraphFor` puts one refusal after another, which is a paragraph rather than a fragment |
| A missing key fails visibly in tests rather than silently at runtime | met | three ways: the bundles are compared key for key; every enum the service can name is worded in every language (`WordingTest`, five tests); and every screen is loaded in both languages by the TestFX suites, where `FXMLLoader` throws on a `%key` nothing answers to. `…aKeyThisBuildShipsNoWordingForThrows` pins the behaviour itself |

## 3. Design decisions a reviewer should judge, not rediscover

- **The language is chosen twice, and the second choice is an admission.** ADR-0014
  is written for a reviewer who asks why a preference the store already holds is
  not applied at the login screen. Short version: before an admission there is a
  name somebody typed, not an Account, and asking the service which language a
  typed name reads would answer a question about an Account to somebody who has
  not proved they hold it.
- **A window says what it is drawn in, and hands on a key for what it is not.**
  `SessionGuard`, `SessionEndedText`, `AdministrationRefusedText` and
  `GateAttempt` answer with a key; the window that draws it words it. The reason
  is the hand-back: the window discovering that a Session ended is closing, and
  the login screen behind it is drawn in another language.
- **The login screen does not keep the admitted Account's language.** It returns
  to the machine's, or to what the selector was set to. Keeping it would tell
  whoever walks up next which language the last person reads.
- **`Locale.ROOT` is how "the machine's" is chosen in the panel's selector**, and
  it is turned into `Optional.empty()` before it crosses the socket. The store
  holds NULL rather than a tag for the same reason V006 gave: an Account that
  follows the machine follows whichever machine it is read on.
- **The service records the tag and holds no list of languages.** It will happily
  record `eu`, which this build ships no wording for, and that Account is then
  drawn in the first language offered — `LanguagePreferenceTest.aLanguageThisBuildShipsNoWordingForIsRecordedAllTheSame`
  pins it. The alternative makes adding a language a privileged deployment.
- **A language with no bundle is drawn in one language, not in a fallback
  mixture.** `ResourceBundle`'s candidate chain stops at the base bundle and never
  reaches the JVM's default locale, so a screen is never half-translated by
  accident, and a regional variant reads the language it varies (`es-MX` → `es`).
- **`LANGUAGE_PREFERENCE_CHANGED` does not say which language.** The record says
  an Account changed; the language itself is in the store and on the panel, and a
  copy in a file read with other tools buys nothing. `…theRecordDoesNotSayWhichLanguageWasChosen`.
- **`GateFlow` exists so the tests can name a language.** `LoginGate.protect`
  gives the machine's, which is the only thing a host product could sensibly be
  given; the suites drive the same flow in a language they name, so what a screen
  says is asserted against the bundle rather than against the locale of whoever
  runs the build.

## 4. Scope taken deliberately, and why

- **No selector on the first-run wizard.** The ticket puts one on the login
  screen. The wizard is seen once, by whoever installed the product on a machine
  whose locale they set; adding one later is small.
- **The `ProtectedFeature`'s own view is left in Spanish.** `protected-feature` is
  the host product in this repo, and CONTEXT.md is explicit that this system knows
  it only as a view it is handed. Translating it would be the gate reaching into
  the host.
- **The selector's choice is not written anywhere.** It lasts as long as the run,
  which is what "for the session" asks for; the durable answer is an Account's
  `LanguagePreference`.
- **Setting a language does not redraw the panel that set it**, even when an
  Administrator sets their own. It takes effect at the next admission, like every
  other fact about an Account.

## 5. Open ground — judge these rather than assume them

- **A preference the panel cannot offer stays as it is.** An Account holding
  `es-ES` shows in the selector as *Español* (the converter names it), and
  applying without touching the box writes `es-ES` back unchanged. That is
  deliberate — the panel does not silently rewrite a tag somebody else chose —
  but it means two Accounts can hold tags that read identically in the list only
  because the tag is shown beside the name.
- **The panel lists a language in that language, not in the Administrator's.**
  `AccountText.preferenceOf` names it in itself with the tag beside it, which is
  what issue #12 shipped and what an Administrator choosing for somebody else
  needs to recognise. A reviewer may reasonably prefer the reader's language.
- **`languages.properties` is a second file to keep in step.** It is the price of
  a list that cannot disagree with itself across bundles; the alternative is a key
  inside every bundle, which is a list that can.
- **Nothing verifies that the offered tags have bundles at build time.** A tag
  added to `languages.properties` without a bundle beside it is drawn in the base
  bundle rather than refused. `WordingTest.everyOfferedLanguageHoldsExactlyTheKeysTheBaseBundleHolds`
  would pass, because that language's file resolves to the base one. Worth a test
  that asserts each offered tag has a file of its own if a third language lands.
- **The TestFX suites are slow** (`AdministrationWindowTest` ~116 s), and this
  ticket added four windows' worth of loading in two languages to them.

## 6. What the two-axis review found and what was done

Reviewed inline rather than by fanning out to sub-agents, on this session's
standing instruction not to spawn them.

### Acted on

| Axis | Finding | What was done |
|---|---|---|
| Spec | **The hand-back sentence was worded in the wrong language.** `SessionController` and `SessionGuard` produced a finished sentence in the Account's language and handed it to a login screen drawn in the machine's, so an Operator reading Spanish logging out of an English-locale machine would have been returned to an English screen with a Spanish sentence on it. | The hand-back carries the **key**: `SessionEndedText.keyFor`, `AdministrationRefusedText.keyFor`, and `GateAttempt`'s `unanswered` all answer with keys, and the login screen words them. `LanguageWindowTest.theLoginScreenComesBackInItsOwnLanguageWhenASessionEnds`. |
| Spec | **The login screen would have leaked the last Account's language** had it kept the language it was handed back in — a fact about an Account, shown to whoever walks up next. | It returns to the pre-authentication language. Same test as above asserts both the screen and its sentence. |
| Spec | **A language change left the previous language's sentence on the screen.** | The window is redrawn saying nothing; `LanguageWindowTest.whatTheOldLanguageSaidDoesNotSurviveTheNewOne`. |
| Standards | **`MessageFormat` treats an apostrophe as quoting**, so an English message with `{0}` in it and an apostrophe in the prose would silently swallow its arguments. | Doubled where needed, and the rule is stated at the top of both bundles. `WordingTest.everyMessageTakesTheSameThingsInEveryLanguage` catches a translation that drops or gains an argument. |
| Standards | **A malformed default locale would have thrown before any window was drawn** — `LanguageRange.parse` refuses a tag it cannot read. | `InterfaceLanguage.closestTo` falls back to the first language offered; `WordingTest.aLocaleThatNamesNoLanguageIsDrawnInTheFirstOneOffered`. |
| Standards | **Stale references to this ticket as future work** in `V006__language_preference.sql`, `AccountListingTest` and `CONTEXT.md`'s `LanguagePreference` entry. | Updated, with `InterfaceLanguage` added to the glossary as the term for what a screen is drawn in — which is not the same thing as what an Account holds. |
| Standards | Lines over 100 columns introduced by widening signatures with an `InterfaceLanguage` parameter. | Rewrapped; the diff introduces none. |

### Deliberately not acted on

- **"`AccountText`, `LockoutText` and `PolicyViolationText` take a language as
  their first parameter" (Standards, Long Parameter List).** They are wording
  functions; the language is what they word in. Making them instances would put a
  constructor call at every call site to save one argument.
- **"`LoginController.admitWith` now takes seven arguments" (Standards).** Six of
  them are the window's collaborators and were already there in some form; the
  alternative is a parameter object that exists to be unpacked immediately.
- **"The base bundle is English while the product's interface is Spanish"
  (Spec).** Deliberate: the base bundle is what a machine this build ships no
  wording for is drawn in, and the repo's own language is English. A Spanish
  deployment is drawn from `messages_es.properties` because the machine says so,
  not because a fallback happened to pick it.
\n
# Code review — Interface language (issue #13), the two-axis review

The section above was written alongside the implementation, and its §6 was an
**inline** review: the two axes were judged by the implementer rather than by
the sub-agents `/code-review` spawns. This section is the skill run properly,
from the same fixed point (`9e05fc7`), against the commit that section describes
(`c16f6bf`) — two independent agents, one per axis, neither seeing the other's
context and neither seeing §6.

It is worth reading for what it says about the inline review: the two axes found
seven things §6 did not, and one of them was a test the implementer wrote that
could not fail.

## Standards axis — what it found

No ADR conflict. ADR-0014, ADR-0002, ADR-0003 and ADR-0007 all hold: no bundle,
sentence or language list reaches `login-core`, `Granted` carries a tag only
after an admission, and the codec writes the explicit `null`.

**Hard violations**

| Finding | What was done |
|---|---|
| **Google Java Style §4.4 (100 columns).** Three added javadoc lines at 102–106 columns, in paragraphs the change reflowed by hand. No formatter or checkstyle plugin exists in `pom.xml`, so nothing but a reviewer catches these. | Rewrapped. `git diff 9e05fc7...HEAD` now adds no line over 100 characters. |
| **`CONTEXT.md` glossary, `InterfaceLanguage` `_Avoid_: locale, i18n, translation, language`.** The type is named right and then referred to by both avoided synonyms: `InterfaceLanguage.locale()`, `InterfaceLanguage language` in six signatures, `ComboBox<Locale> language` in the FXML fields — while the controllers call the same thing `saidIn`. The change disagrees with itself. | **Not acted on.** Recorded as open ground below: it is a rename across eight files, and the right target (`saidIn` everywhere, or `interfaceLanguage`, or leaving `locale()` alone because `java.util.Locale` is what it returns) is a call worth making deliberately rather than at publish time. |

**Judgement calls (Fowler baseline), none acted on**

- **Duplicated Code.** The anonymous `StringConverter<Locale>` whose `fromString`
  throws `"the selector is not typed into"` is written twice, in
  `LoginController.offerTheLanguages` and `AdministrationController.offerTheLanguages`.
  Separately, `refusedSaying(String key)` recurs in `EnrolmentController`,
  `FirstRunController` and, as `failedSaying`, in `LoginController`.
- **Duplicated Code / divergence.** A language is named in itself two ways on one
  screen: `InterfaceLanguage.nameOf` (capitalised `getDisplayLanguage`) for the
  selector, `preference.getDisplayName(preference)` in `AccountText.preferenceOf`
  for the column — so `es-MX` reads differently in each.
- **Primitive Obsession.** A bundle key and a rendered sentence are both bare
  `String`, and `AdministrationController` holds `say(String sentence)` beside
  `theSessionEnded(String saying)`, which takes a key. Swapping them compiles.
  ADR-0014 endorses passing keys; it does not endorse passing them untyped.
- **Data Clumps.** `(gate, stage, protectedFeature, language)` travels through six
  signatures in `LoginWindow` / `GateFlow`.
- **Mysterious Name.** `saidIn`, `THE_MACHINE_S`, `wireWhatIsWorded()`, `theirs()`.
- **Formatting churn.** `GateAttempt.make` and `EnrolmentController.enrolWith`
  were split one parameter per line though the list fits in 99 columns.

## Spec axis — what it found

**(a) Missing or partial**

| Finding | What was done |
|---|---|
| **Criterion 8, "a missing key fails visibly in tests rather than silently at runtime".** `WordingTest.fileFor()` fell back to the base bundle when a language's file was absent, so an offered language shipping **no bundle at all** compared the base bundle against itself and every completeness test passed green. The one case that must fail loudest was the one the test excused. | **Fixed.** `fileFor` no longer falls back: the first offered language is the base bundle, every other one has a file of its own, and `WordingTest.everyOfferedLanguageHasWordingOfItsOwn` names what is missing. Verified by experiment before the fix: with `offered = en, es, fr` and no French bundle, all three completeness tests passed. |
| **Criterion 1, "adding a language touches no code".** It touched test code: `everyOfferedLanguageIsOffered` asserted `List.of(ENGLISH, SPANISH)` verbatim, and `LanguageWindowTest.theSelectorOffersEveryLanguageThisBuildShips` asserted `List.of("English", "Español")`. | **Fixed.** Both now derive from `InterfaceLanguage.offered()`: the first asserts the two languages this product ships are among those offered, the second asserts the selector renders each offered language named in itself. `theClosestOfferedLanguageIsTheOneDrawn` and `aLocaleThatNamesNoLanguageIsDrawnInTheFirstOneOffered` take the fallback from `offered().get(0)` rather than naming English. The measured breakage was two tests, not the three the axis reported. |
| **Criterion 1, "every user-facing string comes from a `ResourceBundle`".** `protected-feature/…/feature-view.fxml` still hardcodes `text="Has accedido a la funcionalidad detrás del sistema de login"`, and it is what an admitted Operator actually reads. | **Not acted on**, and argued: `protected-feature` is the host product in this repo, and CONTEXT.md says this system knows the ProtectedFeature "only as a view it is handed". Translating it would be the gate reaching into the host. The axis is right that it ships here; the answer is that it ships as an example of a host, not as part of the gate. |

**(b) Not asked for**

- `LANGUAGE_PREFERENCE_CHANGED` and its `record(...)` call: the spec asks that an
  Administrator *set* a preference, not that it be audited. **Kept**, and named as
  scope taken: every other Account change in this service is recorded, and one
  that was not would be the gap a reader of the exported record would trip on.

**(c) Implemented but arguably wrong**

- **The base bundle is English while "the product's interface is in Spanish".**
  A machine set to a language this build does not ship is drawn in English, not
  Spanish, and `LoginWindow.theirs()` drops an Account preference this build has
  no wording for to English rather than back to the language the login screen was
  in — so that person loses both their preference and the selector's choice.
  **Not acted on; open for the maintainer.** ADR-0014 records the choice
  deliberately, the Standards axis judged it consistent, and this axis disagrees
  on the ticket's own words. The second half of the finding — the selector's
  choice being lost — is the sharper half and is not settled by that ADR.
- **`PolicyViolationText.paragraphFor` joins sentences with a hardcoded `" "`.**
  **Not acted on.** It is a separator between whole sentences, not a fragment of
  one; a language needing another separator can have the key then.

## What this run says about the inline review

Of the fourteen findings, §6 above had none. Two mattered: a test that could not
fail, and a set of assertions that made the criterion they were checking false.
Both are fixed here. The rest are recorded rather than acted on, with the reason
in each row.

The inline review was not worthless — it caught the hand-back language, the
login-screen leak and the `MessageFormat` apostrophe, all before the commit —
but it did not catch its own tests. That is the argument for the two axes running
somewhere the implementer is not.

---

# Code review — backup export and import (issue #14)

Branch `dev-login`, on top of `55013b3`. One `Administrator` writes a file that
restores the deployment on a machine that has never seen it, and one restores it.

## 1. Where the code is

| Layer | Files |
|---|---|
| The file | `login-core/…/backup/` — `BackupFile`, `BackupContents`, `Backup` |
| Shared crypto | `login-core/…/crypto/` — `AesGcm` and `Utf8` moved out of `vault`, plus the new `PasswordDerivedKey` |
| The store | `CredentialStore.backedUpAccounts/configuration/schemaVersion/replaceEverythingWith`, `account/BackedUpAccount` |
| The wire | `ipc/ExportBackup`, `ImportBackup`, `BackupExported`, `BackupImported`, six `ErrorCode`s, `MessageCodec` |
| The service | `AuthenticationService.exportBackup/importBackup`, `Sessions.endWhateverIsLive`, `SecretVault.destroyEveryWrap`, `Enrolments.oneNobodyHolds` |
| The panel | `LoginGate` ×2, `BackupOutcome`/`BackupWritten`, `RestoreOutcome`/`BackupRestored`, `AdministrationController`, the FXML section, both bundles |
| Decisions | `docs/adr/0015-…`, the `Backup` entry in `CONTEXT.md` |

Tests: `BackupFileTest` (12), `BackupTest` (24), `MessageCodecTest` (+5),
`AdministrationWindowTest` (+6). Full suite green: 540 core, 130 UI, 1 feature.

## 2. What the ticket asked for

All eight criteria are implemented and each has a test that can fail:

| Criterion | Where it is asserted |
|---|---|
| Accounts and configuration, under a password typed at the time | `anAdministratorWritesABackupOfTheAccountsAndTheConfiguration`, `whatTheDeploymentWasConfiguredToDoTravelsWithIt` |
| The `SecretVault` is not in the export | `theSecretVaultDoesNotTravelWithTheBackup` — a secret readable on one machine is `NO_VAULT_ACCESS` on the other |
| Enrolment state excluded | `anEnrolmentInProgressIsNotResurrectedOnTheRestoredMachine` — the old secret is refused, and refused as a login too |
| Restores on a **different machine** | Two `ServiceHarness`es over two `@TempDir`s throughout; no key file is shared |
| Wholesale, never merges | `importReplacesTheStoreWholesaleAndNeverMerges` |
| The `Administrator` is warned first | `restoringABackupStatesWhatItDestroysBeforeItHappens`, `anImportThatIsCancelledAsksTheServiceForNothing` |
| Both directions are `AuthenticationEvent`s | `writingABackupIsRecordedAsAnAuthenticationEvent`, `restoringABackupIsRecordedAsAnAuthenticationEvent` |
| Wrong password or corruption rejected, store untouched | `aBackupOpenedWithTheWrongPasswordIsRefusedAndChangesNothing`, `aDamagedBackupIsRefusedAndChangesNothing` |

## 3. Design decisions a reviewer should judge, not rediscover

- **`AesGcm`, `Utf8` and a new `PasswordDerivedKey` moved to `core.crypto`.** A Backup
  needs exactly what the `SecretVault` needed: Argon2id to a key, then AES-GCM. The
  alternative was a second spelling of AES-GCM next to the second caller, which is how
  a product ends up with two and only one that anybody checks. `KeyEncryptionKey`
  stays in `vault` and delegates: the *concept* is the Vault's and `CONTEXT.md` names
  it, so only the arithmetic moved.
- **Six `ErrorCode`s rather than reusing `EXPORT_*`.** The audit export and the Backup
  follow the same path rule, but they are different files and an `Administrator` told
  "that path was refused" should not have to work out which of the two it was about.
- **The schema version travels and an import refuses anything else.** No migration on
  the way in. An old Backup needs an old build; that cost is accepted in ADR-0015.
- **Two refusals nobody asked for**: `BACKUP_NOT_THIS_SCHEMA` and
  `BACKUP_HAS_NO_ADMINISTRATOR`. The second prevents an unrecoverable state — the
  `FirstRunWizard` is offered only while no `Administrator` exists, and this store
  would have had one. Both are argued in ADR-0015.
- **An import ends the Session that asked.** It named an `Account` in a store that no
  longer exists. Scope taken deliberately; the panel hands the person to the login
  screen of the deployment they restored.
- **A `Lockout` travels.** ADR-0010 makes a Lockout a fact in the store rather than in
  memory, so a restore is not a way to end one.
- **One `PasswordField` on the panel.** It seals a file, belongs to no `Account` and
  admits nobody. `CONTEXT.md`'s `AdministrationPanel` entry now says so rather than
  saying no password may be typed there at all, and `AdministrationWindowTest` pins
  it: the only `PasswordField` on that screen is `#backupPassword`.

## 4. Two-axis review: what was found and what was done

Both axes ran against the staged diff after the suite was green.

### Standards

| Finding | What was done |
|---|---|
| **`AdministrationController` reported an unreadable path on an *import* as `BACKUP_DESTINATION_REFUSED`** — contradicting the javadoc, written in the same diff, that says the two codes are split precisely so nobody has to work out which request a refusal was about. | **Fixed.** `theBackupFile` now takes the reason to say, and the two callers pass their own. |
| **Google Java Style §4.4, 100 columns.** ~68 added lines over. | **Partly fixed, partly argued.** Everything over 103 was trimmed. The rest sit in the 101–103 band the untouched files already occupy (`SecretVault`, `UnlockedVault`, `LoginGate`); no formatter is configured, and reflowing them alone would make this diff inconsistent with its neighbours. |
| **Glossary drift: the record `Backup` is a summary, while `CONTEXT.md`'s `Backup` is the file.** | **Not acted on, and argued.** This is the repo's own established shape: `CONTEXT.md` defines `AuthenticationEventExport` as "a copy … written to one file" and the record of that name is `(long events, boolean chainIntact)`. Deviating here would be the inconsistency. |
| **`BackupCopied` also named a restore**, which is not copying. | **Fixed, and further than asked.** Split into `BackupWritten`/`BackupOutcome` and `BackupRestored`/`RestoreOutcome`, following the `SecretOutcome`/`SecretKeepingOutcome` pair already in the package. |
| **Middle Man: `MessageCodec.destination()`** became a one-line delegate to `path()`. | **Fixed** — inlined. |
| **Speculative generality: `FakeLoginGate.backupsComeTo` unused.** | **Fixed** by using it: the export test now sets `Backup(7, 5)` and asserts both numbers reach the screen, instead of matching the fake's default. |
| **Mysterious name: `AuthenticationService.parameters`.** | **Fixed** — `hashingCost`. |
| **Dead ceremony: `onImportBackup` took a `char[]` only to blank it.** | **Fixed** before the finding arrived, by splitting `theBackupFile()` out so the first click never asks for the password. |
| **Duplicated JSON helpers between `BackupFile` and `MessageCodec`.** | **Not acted on, and argued.** They differ in the exception they raise and in what they are defending against — a hostile peer versus a file somebody chose. Extracting them would couple the wire codec to the backup package through a third. |
| **`isSomewhereThisServiceMayRead` duplicates `…MayWrite`.** | **Not acted on.** Two lines, and the two rules are genuinely different — one is about creating a file, the other about opening one. |
| **Shotgun surgery: six codes × six files.** | **Not acted on.** Pre-existing shape of the refusal path; collapsing it is a change to how every refusal in the product travels, not to this ticket. |

### Spec

| Finding | What was done |
|---|---|
| **An `Operator` whose password an `Administrator` reset was dropped from the Backup entirely** — name, `Role`, language, `Lockout` — because `backedUpAccounts()` filtered `WHERE password_hash IS NOT NULL` and a reset nulls that column. A reset on Monday would quietly delete somebody from every Backup taken before they enrolled again. | **Fixed, and the sharpest finding of the run.** `BackedUpAccount.passwordHash` is now `Optional`; every `Account` travels, no `Enrolment` does, and a restore writes such an `Account` waiting on a secret this machine generated and told nobody. Three new tests: `anAccountThatWasAwaitingEnrolmentIsRestoredStillAwaitingOne`, `anOperatorWhosePasswordWasResetStillTravels`, `anAccountRestoredAwaitingEnrolmentCanBeGivenAFreshSecret`. ADR-0015 rewritten. |
| **Vault wraps are keyed by name, so a restored `Account` could inherit a local namesake's way into this machine's `SecretVault`.** The original test used disjoint names and could not catch it. | **Fixed.** `SecretVault.destroyEveryWrap()`, called on import: after a wholesale replace every name in that table belongs to nobody. The secrets survive under the `MachineKey`. Asserted by `aRestoredAccountDoesNotInheritTheVaultAccessOfALocalNamesake`, which uses the same name on both machines. |
| **The panel never zeroed the `char[]` it handed the gate**, unlike `GateAttempt`'s other callers. | **Fixed** — both backup questions go through `GateAttempt.make(threadName, password, …)`. The javadoc is plain that this shortens the life of one copy and not of the secret: the `PasswordField` holds a `String` nothing here can overwrite. |
| **`blocked-account-names.txt` is configuration and is in no Backup.** | **Not acted on, and now named.** It is a file the installer writes beside the store rather than a row the service owns, so it travels with the installation. Recorded in ADR-0015 so the omission is a decision. |
| Scope taken: sessions ended on import, two extra refusals, `Lockout` travelling, path policing, Argon2 cost ceilings, the `crypto` move. | **Kept**, each argued in §3 or ADR-0015. |

## 5. What a final reviewer should attack first

1. **The placeholder Enrolment.** A restored `Account` awaiting enrolment holds the
   hash of 128 bits nobody was told. It cannot be matched, and `Enrolments` expires it
   like any other — but it is the one row in this system that means "waiting for a
   secret that was never issued", and the schema has no other way to say that.
2. **`destroyEveryWrap` on import.** It is correct for the wholesale case and there is
   no other case, but it is the one place an import touches the `SecretVault` at all,
   and ADR-0006 says the Vault is not a Backup's business.
3. **Refusing a Backup from another schema.** Defensible now, and it is the decision
   that will hurt first — the day somebody upgrades and then needs last month's file.
4. **`MOST_MEMORY_KIB` and friends in `BackupFile`.** The header is a number this
   privileged process allocates against. The ceiling is a guess at what a machine can
   stand.

## 6. Honest limits on what the green build means

- **The `InvalidPathException` branch in the panel is not covered.** On POSIX the only
  string `Path.of` refuses holds a NUL, and neither the TestFX robot nor
  `TextField.setText` will put one in the box. A test was written, could not fail, and
  was **removed** rather than left in — the same trap §7 of the issue #3 review names.
  The branch is asserted by inspection only.
- **"A different machine" is two directories in one JVM.** It proves nothing is shared
  through the two services' files, which is what portability means here; it does not
  prove the file survives a different OS, filesystem or JDK.
- **Nothing asserts the plaintext is unreadable without the password**, only that GCM
  refuses the wrong key. The header test asserts no name, hash or setting appears in
  the clear, which is the reachable half of that.
- **Windows is untested, as everywhere else in this repo.** `OwnerOnlyFiles` narrows
  by ACL there and this file carries every password hash in the deployment.

## 7. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o clean test
mvn -o -pl login-core test -Dtest='Backup*Test'
```

---

# Code review — Linux service activation (issue #15)

Branch `dev-login`, on top of `4fe1da6`. The AuthenticationService already served on a
channel systemd hands over; what it could not do was go away again.

## 1. Where the code is

| Layer | Files |
|---|---|
| The countdown | `login-core/…/authentication/IdleShutdown.java` (package-private) |
| The wiring | `ServiceProcess.serveUntilNobodyIsUsingIt/inUse/close`, `AuthenticationService.anySessionLive`, `TransportServer.anyConnectionLive` |
| The deployment | `installer/linux/javafx-login-authd.socket`, `.service`, `install.sh` |
| The check nobody can automate | `docs/manual-checks/linux-service-activation.md` |
| Decisions | `CONTEXT.md` (`IdleShutdown`), ADR-0002 amended |

Tests: `IdleShutdownTest` (7), `ServiceStopsWhenNobodyIsUsingItTest` (6),
`SystemdUnitFilesTest` (10), `DiagnosticsNeverReachTheClientTest` (1). Full suite green:
564 core, 130 UI, 1 feature.

## 2. What the ticket asked for

| Criterion | Where it is met |
|---|---|
| Connecting activates the service, connection waits in the backlog | Pre-existing `InheritedListeningChannelSource` + the shipped `.socket`; checklist §3 |
| Several Sessions from one process (`Accept=no`) | `Accept=no` asserted by `theSocketIsServedByOneProcessRatherThanOnePerConnection`; checklist §4 |
| Exits by itself after five idle minutes, socket stays listening | `IdleShutdownTest`, `ServiceStopsWhenNobodyIsUsingItTest.stopsTheProcessOnce…`; checklist §6 |
| Re-activation works repeatedly | Nothing in the process removes the socket file (`InheritedListeningChannelSource.release()` is empty by design); checklist §7 |
| `SocketUser=`/`SocketGroup=`/`SocketMode=` declarative | `theSocketsOwnershipAndModeAreDeclaredRatherThanLeftToUmask`; checklist §2 |
| A dedicated group, not a user's primary group | `install.sh: create_dedicated_group` (`groupadd --system`); asserted against the unit by the same test |
| Diagnostics to the journal, never into a connection | `theServicesDiagnosticsGoToTheJournalAndNotIntoAClientConnection` **and** `DiagnosticsNeverReachTheClientTest` — the second is the half that survives a unit file being edited |
| No rate-limiting or `Lockout` state in memory across the shutdown | Pre-existing: `Lockouts` and `Enrolments` write through `CredentialStore`. This change adds no counter |
| A documented manual verification checklist | `docs/manual-checks/linux-service-activation.md`, eight steps, each naming what *is* automated |

The four silent traps are each pinned by a test that reads the shipped file: one
`ListenStream=`, `StandardInput=socket`, both output streams set, and no `[Install]` in
the `.service`.

## 3. Design decisions a reviewer should judge, not rediscover

- **In use means a live Session *or* a live connection, and ADR-0002 was amended to say
  so.** The ticket says "five minutes with no `Session`s". A connection with no Session
  behind it is a person at the login window who has not typed a password yet, and
  `ServiceLoginGate` opens one connection on the first attempt and keeps it — exiting
  under them would drop the channel their next attempt goes over. Nothing is given up:
  the kernel closes a connection when the client process dies, and the five minutes
  begin there. **This is the one place this change is wider than its ticket**, and the
  Spec axis flagged it as such.
- **Polling every 15 s rather than being told.** Being told means every path that opens
  or ends a Session or a connection remembering to say so, and one that forgot would
  leave a privileged JVM up for good. The cost is that the process can outlive its five
  minutes by up to 15 s.
- **The monotonic clock alone**, unlike a Session (two clocks) and a Lockout (wall clock).
  This countdown measures nothing but this process's own life and never outlives it, so
  setting the machine's time neither ends a privileged process early nor keeps one alive.
- **`anySessionLive()` does not expire.** ADR-0009 makes expiry something the service
  decides when somebody *asks about a Session*; this caller is asking whether a process
  should stop. A Session whose clocks have run out is still held by a connected client,
  and the connection keeps the service up in that case anyway.
- **The units are the artifact the spike measured**, minus nothing and plus nothing:
  `RemoveOnStop=`, `SuccessExitStatus=` and `Restart=` were written and then removed for
  exactly that reason.

## 4. Two-axis review: what was found and what was done

### Standards

| Finding | What was done |
|---|---|
| ADR conflict left unsurfaced: ADR-0002/0009/0010 all say "without Sessions", the code says "or a connection" | **Fixed.** ADR-0002 carries an explicit amendment naming issue #15, and says why the widened reading leaves ADR-0009/0010's reasoning untouched |
| Six new lines over Google style's 100 columns | **Fixed**, all six reflowed |
| Glossary drift: `CONTEXT.md`'s **Peer** avoids "client" | **Partly fixed.** Prose that names the far end of the socket now says "peer" where it reads naturally. Not chased into `ServiceClient`/`TransportClient` or the unit-file comments, where "client connection" is the systemd term of art the spike itself uses |
| `anySessionLive()` sits beside `theMachineIsBusy()`, one question with two names | **Fixed** by javadoc on both: the difference is whether expiry runs first, and which caller needs it |
| `IdleShutdown.hasStopped()`, `IDLE_PERIOD` and `ServiceProcess.inUse()` public for tests only | **Fixed.** `IdleShutdown` is package-private in full, and `inUse()` with it |
| Test duplication: the clock-and-countdown pair repeated verbatim | **Fixed**, folded into `theCountdownAgainstThisProcess()` |
| Redundant state in `IdleShutdown` (`stoppedTheService`, `watchingIsOver`, nullable `watching`) | **Kept.** They are three facts, not one: the service was told to stop, the loop must end, and there is a thread to interrupt. `close()` sets the second without the first |

### Spec

| Finding | What was done |
|---|---|
| `install.sh` never puts anything under `/opt/javafx-login`, so a machine it installed would have a listening socket and an `ExecStart` that dies (203/EXEC) | **Fixed.** `require_payload` reads the launcher path out of the `.service` itself and refuses to enable anything if it is not there; checklist gained a §0 for it. Building and placing the payload stays outside this ticket |
| Scope creep: live connections keep the process alive | **Kept and argued** — §3 above, and ADR-0002 amended |
| `RemoveOnStop=yes` not in the spike's artifact list | **Removed** |
| `SuccessExitStatus=0`/`Restart=no` are defaults | **Removed** |
| `CHECK_INTERVAL` of 15 s means up to 15 s past the five minutes | **Kept**; the checklist says "about five minutes" |

## 5. What a final reviewer should attack first

1. **The widened reading of "idle".** A client process that stays alive and connected
   holds a privileged JVM up indefinitely. That is the login window, by design — but it
   is the one property of this ticket that a hostile client can lean on.
2. **`ExecStart` is a path nothing in this repo produces.** There is no packaging step
   here: no jlink, no jars laid down, no uninstall script. `require_payload` turns that
   into a loud failure rather than a quiet one, and nothing more.
3. **Every unit-file assertion is a string comparison.** The tests read the shipped files
   and check settings the spike named. They cannot tell whether systemd still reads them
   the same way, which is what §§1-7 of the checklist are for.
4. **`SocketGroup=javafx-login` is a name in three places** — the unit, `install.sh` and
   `SystemdUnitFilesTest.DEDICATED_GROUP` — and nothing makes them one.

## 6. Honest limits on what the green build means

- **Nothing in the suite has ever been socket-activated.** systemd is not in the build.
  Every automated assertion here is either about the countdown (with the clock moved by
  hand) or about the text of two files. The mechanism itself was proven once, by hand,
  in the spike, and is re-proven only by the manual checklist.
- **`install.sh` has never been run by the suite.** It is checked by `bash -n` and by
  reading.
- **The idle exit is asserted through `ServiceProcess.close()`, not through a JVM
  exiting.** That the JVM then leaves — and that systemd re-activates it — is checklist
  §§6-7.
- **Windows keeps none of this.** `PlatformListeningChannelSource` still refuses there,
  and the Manual-start service with its start-then-wait dance remains designed and
  unbuilt.

## 7. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o clean test
mvn -o -pl login-core test -Dtest='IdleShutdownTest,ServiceStopsWhenNobodyIsUsingItTest,SystemdUnitFilesTest,DiagnosticsNeverReachTheClientTest'
bash -n installer/linux/install.sh
```

# Code review — client startup diagnostics (issue #16)

The application refusing to start when the `AuthenticationService` cannot be reached, and
saying which of three things is the reason. Written for a final reviewer who has not
followed the ticket.

## 1. Where the code is

**The wire (`login-core/.../ipc`)**

| File | What it is |
| --- | --- |
| `ProtocolVersion.java` | `CURRENT = 1`, and the rule freezing the exchange below |
| `AskWhichProtocolIsSpoken.java` / `ProtocolSpoken.java` | the frozen question and its answer |
| `ServiceReachability.java` + `Reachable` / `Unreachable` | what a client found before drawing anything |
| `ServiceUnreachableReason.java` | the three: `NOT_RUNNING`, `INCOMPATIBLE_VERSION`, `SOCKET_NOT_ACCESSIBLE` |
| `ServiceHandshake.java` | the probe: non-blocking connect + handshake against one deadline |
| `MessageCodec.java` | both new messages, and `version()` refusing anything that is not one |

**The service** — `AuthenticationService.handle` answers `AskWhichProtocolIsSpoken` first,
out of a constant, before the `CredentialStore` is touched.

**The client (`login-ui/.../login`)**

| File | What it is |
| --- | --- |
| `LoginGate.reachability()` | the new question, off the JavaFX thread |
| `ServiceLoginGate.reachability()` | delegates to `ServiceHandshake`; unsynchronised, cached nowhere |
| `GateFlow.java` | asks both startup questions off-thread, then opens one of three windows |
| `ServiceUnreachableWindow` / `Controller` / `Text` | the refusal, its FXML and its three keys |
| `messages.properties` / `messages_es.properties` | five keys each |

**Docs** — `ADR-0016`, two `CONTEXT.md` glossary entries (`ProtocolVersion`,
`ServiceReachability`), and a paragraph in the README's "Running the pair by hand".

## 2. What the ticket asked for

| Criterion | Where it is met |
| --- | --- |
| Refuses to start rather than degrading | `GateFlow.whatToOpen`; `NoServiceAtStartupTest` |
| Three cases distinguished, each naming its remedy | `ServiceUnreachableReason` + `ServiceUnreachableText`; `ServiceHandshakeTest` pins all three separately |
| Version negotiated, mismatch reported as such | `AskWhichProtocolIsSpoken` → `ProtocolSpoken`; `ProtocolVersionTest`, `MessageCodecTest` |
| Bounded timeout, UI thread not hung | `ServiceHandshake.PATIENCE` (5 s, one deadline for the whole exchange); `GateAttempt` runs it off-thread |
| Failures in translated strings | both bundles; `WordingTest.everyReasonTheServiceCannotBeReachedIsWordedInEveryLanguage` |
| Exercised at Seam 1 or Seam 2, no installed service | Seam 1 `ProtocolVersionTest`; Seam 2 `ServiceHandshakeTest` (11 tests, sockets in a `@TempDir`) |

## 3. Design decisions a reviewer should judge, not rediscover

1. **The handshake is frozen forever.** Every version must read and write those two
   messages unchanged, or two disagreeing builds lose the one exchange they can both
   complete and the disagreement is a parse failure again. Stated on `ProtocolVersion`,
   argued in ADR-0016, pinned by a round-trip test — which is the most a codec can do
   about a promise spanning releases.
2. **Silence is `NOT_RUNNING`, and that is socket activation showing through.** systemd
   holds the socket open, so the kernel accepts the connect whether or not anything came
   up behind it. There is nothing else for a client to observe. The deadline is what turns
   silence into a sentence.
3. **The probe has its own connection and its own clock.** `TransportClient` carries
   Sessions — an Argon2id verification and a whole `Backup` cross it — and must never be
   given up on. Bounding it would abandon work that was going fine.
4. **The three are told apart by exception type, never by message text.** The JDK reports
   a refused `AF_UNIX` connect as `BindException` on `EACCES`, `ConnectException` on
   `ECONNREFUSED` and plain `SocketException` on `ENOENT`; the messages behind those are
   the operating system's, in the machine's own language.
5. **A non-blocking channel and a selector, not a second thread with a stopwatch.**
   Measured: a connected, silent channel registered for `OP_READ` costs 2 `select()` calls
   across 500 ms, so the loop blocks rather than spins.

## 4. Two-axis review: what was found and what was done

**Spec axis — 3 findings.**

1. *Not every startup refusal named a remedy* — a service that answered the handshake and
   not the next question produced "could not be reached" with no remedy. **Fixed**:
   `GateFlow.whatToOpen` catches it and refuses with `NOT_RUNNING`, which names one.
2. *Only the first of the two startup round trips is bounded* — `firstRunNeeded()` goes
   over `TransportClient`, which has no read timeout, so a service that answers the
   handshake and then wedges leaves the stage empty. **Not fixed, and the finding's
   framing is corrected below** — see §6.
3. *`INCOMPATIBLE_VERSION` is the catch-all for anything unparseable* — a foreign process
   squatting on the socket is told "install the product again". **Accepted as recorded**:
   ADR-0016 argues it, and the socket lives in a directory only two accounts can reach.
   Noted as a limit rather than fixed.

Also: a duplicated assertion in `ProtocolVersionTest`. **Fixed** — it now asserts the
answer does not change once the deployment has an `Administrator`.

**Standards axis — 4 hard, 4 judgement calls.**

1. *Google §7.2: four Javadoc comments were a block tag with no summary fragment.*
   **Fixed** in `GateFlow`, `ServiceUnreachableController` and both
   `ServiceUnreachableWindow.show` overloads.
2. *Google §4.4: one code line at 102 columns* in `GateFlow`. **Fixed.**
3. *`ClosedByInterruptException` swallowed into `NOT_RUNNING`.* **Fixed**: caught
   separately and the interrupt flag restored.
4. *`waitFor` ignored `select()`'s return and answered about the clock.* **Fixed by
   renaming** to `stillHasTimeAfterWaiting`, which is the question every caller asks; the
   Javadoc now says why readiness is deliberately not reported.
5. *Redundant `key.interestOps(OP_CONNECT)`.* **Fixed** — removed.
6. *Duplicated Code: two identical catch bodies.* **Fixed** — one multi-catch.
7. *Middle Man: the `unreachable(...)` helper.* **Fixed** — inlined.
8. *Mysterious Name: thread named `startup-diagnostics`.* **Fixed** — `service-reachability`,
   which is the glossary's word.

**Declined, with reasons.**

- *"story 90 and #16 cite two trackers."* `story NN` is established house style throughout
  this codebase for the numbered user stories of issue #1 — `git grep "story [0-9]"` finds
  it in a dozen files predating this change. Both identifiers are correct and mean
  different things.
- *Primitive Obsession: `ProtocolVersion` holds no value; the wire carries a bare `int`.*
  The reviewer supplied the counter-argument: ADR-0016 freezes `ProtocolSpoken`'s shape, so
  wrapping the field would be churn on a message that may not change.

## 5. What a final reviewer should attack first

1. **The freeze is a promise, not a mechanism.** Nothing stops a later commit editing
   `ProtocolSpoken`. The round-trip test would still pass — it round-trips whatever the
   record currently is. Only the Javadoc and ADR-0016 defend it.
2. **`PATIENCE` is five seconds against a `synchronized` service.** `handle` serialises
   every request, so a startup handshake queues behind another client's long operation. A
   `Backup` import that takes longer than five seconds would report `NOT_RUNNING` about a
   service that is working perfectly. Nothing in the suite covers this.
3. **`BindException` for `EACCES` is a JDK mapping, not a specification.** It is pinned by
   a real `chmod 000` test, which is what would catch it changing — but that test skips
   under root, so a root CI would go green on a broken diagnosis.
4. **Nothing has been socket-activated.** As with issue #15: the `NOT_RUNNING`-by-silence
   case is proven against a stub that stays quiet, never against systemd.

## 6. Honest limits on what the green build means

- **The second startup round trip is unbounded, and this change made that strictly
  better rather than worse.** The spec reviewer read it as a regression; it is not. Before
  this change `GateFlow.open` called `firstRunNeeded()` **on the JavaFX application
  thread** — `git show 2441f7a:.../GateFlow.java` — so the same wedged service froze the
  whole toolkit. It now leaves the toolkit responsive with an empty stage. Bounding it
  properly means a general request timeout, which the ticket did not ask for and which
  would report `NOT_RUNNING` about a service legitimately busy with a slow `Backup`.
  Left as it is, deliberately, and recorded here.
- **`INCOMPATIBLE_VERSION` is wider than the ticket's word.** Anything that answers and is
  not this build's frozen message lands there — a corrupt frame, a foreign process. The
  remedy it names ("install the product again") is right for the case the ticket meant and
  wrong for the ones it did not.
- **Seam 3 drives a fake gate, as it must.** `NoServiceAtStartupTest` proves which window
  appears for each reason; it proves nothing about how a reason is arrived at, which is
  Seam 2's job.
- **Windows keeps none of this.** Its client must start the service and wait for the socket
  to appear, so "not running" is directly observable there and the timeout means something
  else. The reasons and the handshake are shared; only what produces them differs.
- **The JavaFX runtime surviving an empty `start()` was measured, not assumed.** A window
  put on the stage 1.5 s after `start()` returned with nothing shown still arrives.

## 7. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o clean verify
mvn -o -pl login-core test -Dtest='ServiceHandshakeTest,ProtocolVersionTest,MessageCodecTest,ServiceOverTheSocketTest'
mvn -o -pl login-ui test -Dtest='NoServiceAtStartupTest,WordingTest'
```

# Code review — Linux packaging (issue #17)

Branch `dev-login`, on top of `94990a3`. The machine already knew how to start the
AuthenticationService on demand; what nothing in this repository could do was put the
product on a machine in the first place.

## 1. Where the code is

| Layer | Files |
|---|---|
| The build | `installer/linux/build-deb.sh` (jlink, jpackage, one payload and two launchers) |
| What the package does to a machine | `installer/linux/debian/postinst`, `prerm`, `postrm` |
| The wiring itself | `installer/linux/install.sh` — unchanged in what it does, now run by the postinst too |
| The units | `javafx-login-authd.socket`, `.service` (`ExecStart=` is now the packaged launcher) |
| Migrating at install time | `ServiceProcess.bringTheFilesUpToDate` / `--upgrade`, `AuthenticationService.schemaVersion` (package-private) |
| The licence obligation | `installer/linux/THIRD-PARTY-NOTICES.md`, `installer/linux/debian/copyright` |
| The check nobody can automate | `docs/manual-checks/linux-packaging.md` |
| Decisions | ADR-0017, `CONTEXT.md` (`Deployment`, `Purge`) |

Tests: `UpgradeBringsTheFilesForwardTest` (4), `DebianPackageTest` (13),
`TheTrimmedRuntimeCarriesEveryOfferedLanguageTest` (1),
`TheInstalledSocketIsTheOneSystemdListensOnTest` (1). Full suite green: 599 core, 138 UI,
2 feature. The `.deb` was built and inspected on Ubuntu 26.04 with JDK 21.0.12, and the
packaged application was started from the built image.

## 2. What the ticket asked for

| Criterion | Where it is met |
|---|---|
| `jlink` trims a runtime, `jpackage` makes an installable `.deb` | `build-deb.sh: link_the_runtime`, `build_the_application_image`, `build_the_package`; built and inspected with `dpkg-deb -c/-I` |
| The protected directory, with the right owner and mode | `install.sh: create_state_directory` (`install -d -o root -g root -m 0700`), run by the postinst |
| The dedicated group `SocketGroup=` names, both units registered, only the `.socket` enabled | `install.sh: create_dedicated_group`, `install_units`, `enable_the_socket_only`; `SystemdUnitFilesTest.onlyTheSocketIsEnabledAtBoot` |
| The OpenJFX attribution ships with the package | `THIRD-PARTY-NOTICES.md` at `/opt/javafx-login/lib/doc/`, `debian/copyright` at `/opt/javafx-login/share/doc/copyright`; `DebianPackageTest.theAttributionTheGplRequiresIsInThePackageRatherThanInTheRepository` |
| Reinstalling reasserts permissions | The postinst runs `install.sh` on every `configure`; `DebianPackageTest.everyUpgradeAssertsThePermissionsOnTheDirectoryItCannotSeeInside`; checklist §5 |
| Reinstalling preserves Accounts, configuration, `SecretVault` | Nothing in any maintainer script touches `/var/lib/javafx-login` outside `purge`; `DebianPackageTest.anUninstallKeepsTheDeploymentAndOnlyAPurgeDestroysIt`; checklist §5, §7 |
| Uninstalling keeps that data by default | `postrm remove` says it kept it and how to destroy it; same test; checklist §7 |
| An explicit purge that states what it destroys | `postrm purge`; `DebianPackageTest.thePurgeSaysWhatItIsDestroyingWhileItIsStillThere` asserts it says it *before* it does it; checklist §8 |
| Migrations on upgrade; a newer store refuses to start | `ServiceProcess --upgrade` from the postinst; `UpgradeBringsTheFilesForwardTest` (4 cases, including the refusal with both version numbers); checklist §6 |
| A clean Ubuntu machine yields a working login with no manual step | `postinst` admits the installing account to the group; checklist §§1-3 — with one honest exception, below |

## 3. Design decisions a reviewer should judge, not rediscover

- **The package installs an application and never a deployment (ADR-0017).** A `postinst`
  that created a CredentialStore would bring a deployment into existence on a machine
  nobody has logged into, which ADR-0008 and ADR-0012 give to the FirstRunWizard alone.
  So `bringTheFilesUpToDate` returns `0` and writes nothing where there is no store, and
  `apt remove` leaves every Account where it is.
- **The postinst runs `install.sh` rather than repeating it.** Two implementations of
  "what a machine needs" would be two places for a mistake, and the one being debugged
  would be whichever the person happened to be reading.
- **Migrations move to install time.** Under socket activation a service that cannot open
  its files is indistinguishable from one nobody has connected to, so a failed migration
  would surface as "the AuthenticationService is not running", days later, to somebody who
  was told the installation succeeded.
- **The `--add-modules` list is written out, not derived.** `jdeps` reads bytecode, and
  neither of the two modules that matter is visible there: `java.sql` is reached through a
  driver loaded by name, and `jdk.localedata` holds the names languages call themselves.
- **The installing account is admitted to the group.** `sudo` and `pkexec` each say who
  they are acting for; membership admits nobody to anything without a password, and
  without it the person who just installed the product cannot reach the socket at all.
- **One payload, two launchers.** The window and the privileged process share every jar,
  because they share a protocol and a package with two copies of it could ship two
  versions of it. The service launcher is kept out of the applications menu
  (`linux-shortcut=false`): started by hand it inherits no socket and refuses to start.
- **`--launcher-as-service` was rejected.** jpackage would generate a service unit and
  enable it at boot, which is the privileged JVM on an unattended machine that ADR-0002
  and the whole of issue #15 exist to avoid.

## 4. What the packaging found in code that was already green

Two disagreements that install cleanly, run, and say nothing:

1. **The two halves named different sockets.** `ProtectedFeatureApplication` connected to
   `/run/javafx-login/authentication.sock` while the shipped `.socket` unit listened on
   `/run/javafx-login-authd.sock`. Nothing reconciles those at run time, and under socket
   activation the client's report for "nothing answered" is *not running* — so a packaged
   installation would have said the AuthenticationService was down on a machine where it
   was installed and well. `TheInstalledSocketIsTheOneSystemdListensOnTest` now holds the
   constant against the unit file.
2. **A trimmed runtime loses the name of a language.** Without `jdk.localedata` the
   selector offers "Spanish" instead of "Español" — in the one screen ADR-0014 exists for,
   to the person who is choosing that language because the other one is not theirs. Found
   by running the whole suite on the trimmed runtime (`mvn test -Djvm=…`);
   `TheTrimmedRuntimeCarriesEveryOfferedLanguageTest` now fails when a language is added
   to `languages.properties` and not to the build.

## 5. What the two-axis review found and what was done

Both axes ran against `94990a3...baabc8d`. Eleven findings on Standards, eleven on Spec;
the ones that mattered were three ways a machine could be left in a state nobody would
notice.

### Applied — the three that were real faults

1. **The socket stayed listening through an upgrade.** (Spec, c1.) `prerm` stopped the
   `.service` and disabled the `.socket` only on `remove`. dpkg unpacks the new payload
   immediately afterwards, so any connection in that window activated a privileged JVM on
   a half-replaced `/opt/javafx-login` — the exact thing that file's header claims to
   prevent. The socket is now stopped for `upgrade` as well, and the postinst enables it
   again once the machine is wired. `theSocketStopsForAnUpgradeAndNotOnlyForARemoval`.
2. **The downgrade refusal fired after the socket was already listening.** (Spec, c4.) The
   postinst wired the machine and *then* migrated, so a store from a later build failed an
   installation that had already enabled the socket: the next login would activate a
   service that dies on a file it cannot read, and be told the AuthenticationService is not
   running — which is the silence ADR-0017 says the install-time migration exists to avoid.
   The two steps are now the other way round, and the reason is written where the order is.
   `aRefusedUpgradeLeavesNothingForAnybodyToConnectTo`.
3. **A `systemctl` that cannot run failed the installation.** (Spec, c2.) `install.sh`
   assumed a booted systemd, so `apt install` inside a chroot or a container image died and
   left the package half-configured. `systemd_is_running` now separates *installed* from
   *running*: the group, the directory, the units and the documents are put in place either
   way, and what could not be enabled is said out loud with the command that enables it.
   Checklist §9.

### Applied — the smaller ones

4. **A deleted `SUDO_USER` failed a finished installation.** (Spec, c3.) `install.sh`
   refuses a name it cannot find, which is right for somebody typing one; arriving through
   the postinst it failed an installation whose group, directory and units were already
   correct, over the last and least important step. The postinst now treats an account that
   does not exist as nobody — and says so, with the command to fix it, rather than leaving
   a person at a login window reporting that the service is not running. (That message also
   answers Spec (a): `apt` from a root shell or from PackageKit admits nobody.)
5. **Nothing held `SocketGroup=` against the group the installer creates.** (Spec, a2.) The
   identical drift for `ListenStream=` had a test and this did not.
   `theGroupTheSocketIsReadableByIsTheGroupTheInstallerCreates`.
6. **`stage_the_deployment_files` inverted the glossary.** (Standards, 2.) A `Deployment` is
   precisely what the package must never carry — ADR-0017 is named after it. Renamed
   `stage_the_units_and_documents`.
7. **`bringTheFilesUpToDate` was public** with no caller outside its package, while the
   `schemaVersion()` it reads had been made package-private with a sentence saying why.
   (Standards, 5.) Both are package-private now, and both say so.
8. **`main` tested `args.length` in three places.** (Standards, 11.) One method,
   `channelNamedIn`, now decides the arity and `boundToTheSocketNamedIn` is gone with it.
9. **`DESKTOP_SCRIPTS` had been dropped from the prerm.** (Spec, c5.) It expands to nothing
   today and to jpackage's mime-type helpers the day this product declares a file
   association; leaving it out would produce a prerm calling functions nobody defined. It is
   back — and `package_type=deb`, which only `services_utils.sh` reads and this package does
   not use, is gone (Standards, 10).
10. Wording and naming: `read()` → `readTheInitialSchema()`, a comment on `PAYLOAD` that
    described `lib` while naming the payload root, `--include-locales` described as tags
    rather than as languages the glossary keeps apart, and `FirstRunWizard` spelled as the
    glossary spells it in the README. (Standards, 3, 4, 7, 8.)

### Judged and not applied

- **"No manual step" is not literally true, and the checklist says so in a heading.**
  (Spec, a1.) A group membership does not reach a session that already existed, and no
  package can change that. What *was* in scope — that somebody is admitted at all, and that
  a person is told when nobody was — is finding 4.
- **`installerDirectory()` is copied into three test classes in three modules.**
  (Standards, 6.) Sharing ten lines across Maven modules means a `test-jar` and a new
  dependency in two poms, to hold a walk-up that has not changed since it was written.
  Recorded here rather than paid for.
- **The scope the Spec axis flagged.** (Spec, b.) The socket-path fix is what makes a
  packaged login work at all, `jdk.localedata` is what makes it work in Spanish, the smoke
  check is what keeps a broken image from being packaged, and admitting the installing
  account is the difference between an installation and an installation plus an
  undocumented step. `install_documentation` follows from `Documentation=` having to name a
  path that exists. Each is named in §3 above, and each is defended there.
- **`$(installing_account)` is deliberately unquoted** (Standards, 10), because an empty
  result must become no argument rather than an empty one. The comment above it now says so.

## 6. What a final reviewer should attack first

1. **`postinst` is `set -e` over three commands that touch the machine.** If `install.sh`
   fails halfway — no `systemctl`, a `groupadd` that is refused — the package is left
   unconfigured with its payload in place, which is dpkg's normal failure shape but is
   worth judging against what a half-wired machine looks like to its owner.
2. **The `--upgrade` run in `postinst` opens the real files with production Argon2
   parameters and then closes them.** It writes nothing but migrations, and it is the only
   time this product's privileged code runs outside socket activation.
3. **Nobody is admitted when `apt` is run from a root shell.** `SUDO_USER` and
   `PKEXEC_UID` are how the installing person is identified, and neither is set then. The
   checklist says so and names the remedy, but it is the one case where "no manual step"
   is not true.
4. **The layout knowledge is in four files.** jpackage puts `--app-content` under the
   image's `lib/`; the postinst, both units and `install.sh` name those paths.
   `DebianPackageTest` holds them to each other, but nothing holds them to jpackage.
5. **`groupdel` on purge.** If a person is still a member and a gid is later reused, the
   meaning of that membership moves. It is done on purge only, and never on removal.

## 7. Honest limits on what the green build means

- **No test installs anything.** dpkg is not in the suite, and every automated assertion
  about the package reads a maintainer script as text. The install, upgrade, remove and
  purge paths are `docs/manual-checks/linux-packaging.md` and nothing else.
- **The `.deb` in this review was built and inspected, not installed.** `dpkg-deb -c`,
  `dpkg-deb -I` and reading the substituted maintainer scripts out of the package: the
  token substitution, the single desktop entry, the `0755` on `lib/systemd/install.sh` that
  the postinst invokes directly, and the order of the two steps in the postinst. The smoke
  check in `build-deb.sh` ran the packaged service launcher on the linked runtime, and the
  packaged window was started by hand from the built image — it drew and stayed up, with no
  output but the classpath warning ADR-0007 predicts. It was not looked at: this machine has
  no screenshot tool, so "a login window appeared" is not something this review can claim.
- **The trimmed runtime was verified by running the whole suite on it**, with
  `java.management` added for Surefire's forked booter and for nothing the product uses.
  That is the only difference between the image tested and the image shipped.
- **`fakeroot` is jpackage's dependency, not this repository's.** Without it jpackage
  *skips* the DEB bundler and exits successfully, which is a build that produces nothing
  and says so quietly. `require_tools` refuses first.
- **Windows keeps none of this.** The `.deb`, the units and `install.sh` are Linux, and
  the Windows service remains designed and unbuilt.

## 8. Reproducing

```bash
# from the repo root, on branch dev-login
mvn -o clean test
mvn -o -pl login-core test -Dtest='UpgradeBringsTheFilesForwardTest,DebianPackageTest,SystemdUnitFilesTest'
mvn -o -pl login-ui test -Dtest='TheTrimmedRuntimeCarriesEveryOfferedLanguageTest'
mvn -o -pl protected-feature test -Dtest='TheInstalledSocketIsTheOneSystemdListensOnTest'

sudo apt install fakeroot
./installer/linux/build-deb.sh --skip-tests
dpkg-deb -I target/package/dist/javafx-login_0.1.0_amd64.deb
dpkg-deb -c target/package/dist/javafx-login_0.1.0_amd64.deb | grep -E 'systemd|lib/doc|bin/'

bash -n installer/linux/build-deb.sh installer/linux/install.sh
sh   -n installer/linux/debian/postinst installer/linux/debian/prerm installer/linux/debian/postrm
```
