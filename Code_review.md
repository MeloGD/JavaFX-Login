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
