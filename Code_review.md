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
