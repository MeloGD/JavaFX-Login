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
