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
