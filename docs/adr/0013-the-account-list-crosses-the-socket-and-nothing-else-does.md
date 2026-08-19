# The account list crosses the socket, and nothing else about an Account does

The AdministrationPanel is a list of Accounts and a handful of things to do to
one of them. Every one of those things already existed as a request the
AuthenticationService answers — `CreateAccount`, `InitiateReset`,
`DeleteAccount`, `ClearLockout`, `ChangeInactivityPeriod`,
`ExportAuthenticationEvents`. The list did not, and adding it is the one decision
in issue #12 worth recording, because on its face it contradicts ADR-0002.

ADR-0002 states the security property this whole project exists for: an
operating-system account without elevated privileges cannot read the account
list or any password hash. This ADR hands a client running as exactly that
account a list of every Account in the deployment.

**The decision.**

- `ListAccounts` carries a `SessionToken` and nothing else, and is refused unless
  the Session it names was granted in the Administrator Role. It is refused in
  the privileged process, alongside every other administration request, so a
  client patched into drawing the panel draws an empty one and is refused
  everything it clicks.
- What comes back is an `AccountSummary` per Account and **not an `Account`**.
  The two are separate types on purpose: an `Account` carries the password hash,
  and this one has no field a hash could travel in even if a later build were
  careless with the query behind it. The query is written column by column rather
  than as a `SELECT *` for the same reason.
- A summary carries the name, the Role, the coarse `PasswordStrength` band, the
  `LanguagePreference` and the `Lockout`. Those five are what issue #12 asks the
  panel to show, and nothing else is sent because nothing else is shown.
- The `Lockout` is filled in by the service and not by the store. The
  CredentialStore holds the moment a refusal runs out and has neither a clock nor
  the `LockoutPolicy` to read it against; the service asks the same `Lockouts`
  component the login screen's refusals go through, so the panel and the login
  screen cannot come to disagree about who is locked out — and a Lockout that has
  run out is forgotten as it is read, here exactly as it is there.
- Listing is **not** an AuthenticationEvent. The record is of things that
  happened to Accounts, and an Administrator looking at the screen they
  administer the deployment from has changed nothing. What they go on to do from
  it is recorded where it is done.

## Why this does not undo ADR-0002

ADR-0002's property is about an attacker who has an operating-system account and
no password of this system. That attacker cannot send `ListAccounts` to any
effect: the request requires a `SessionToken` the service issued, and the service
issues one only after verifying an Argon2id hash it alone can read. Reading the
store directly is what the file mode refuses, and that has not changed.

What crosses here is what an authenticated Administrator is for. The alternative
— an Administrator who manages Accounts they cannot see — is not a security
property, it is a panel that cannot be drawn, and the deployment would go on
being administered by somebody editing the SQLite file as root, which is the one
route ADR-0001 never claimed to close.

The band is worth one more sentence, because it is the field that looks like a
leak. `PasswordStrength` is three constants and the score behind it is discarded
where it is estimated (V002), precisely so that no file and no screen can rank
the Accounts of a deployment by how cheap each one is to attack. A panel that
showed a number would be building that ranking for whoever is at the keyboard;
one that shows a band tells an Administrator which colleagues are worth a
conversation and no more.

## The panel is a window over a Session, like every other one

An Administrator's Session is a Session: it is bound to the connection it was
granted on, it expires on inactivity by the `InactivityPeriod` the deployment is
configured with, and it ends when the client dies. The panel therefore carries a
`SessionGuard` exactly as the window an Operator works in does, closes when the
service says the Session is over, and hands the person back to the login screen
with a sentence saying which of the four things ended it.

Two smaller consequences of that shape:

- The panel is opened from the login screen and the host product's view is never
  built on that path. Story 38 says an Administrator does not reach the
  ProtectedFeature; the strongest way to say it in a client is not to construct
  it.
- One machine holds one Session at a time, so an Administrator at the panel and
  an Operator at the feature cannot both be logged in. That was already the rule
  and it is not softened here.

## What was considered and rejected

- **One request per Account, rather than a list.** Rejected: it is the same
  disclosure spread over more round trips, and it would let a Session that is not
  an Administrator's ask the same questions one name at a time if the refusal
  were ever weakened. The list is refused once, in one place.
- **Sending the whole `Account`, hash included, and letting the client show what
  it likes.** Rejected on sight. It is the exact failure ADR-0002 exists to
  prevent, written by hand.
- **Recording an AuthenticationEvent for every listing.** Rejected: the panel
  asks for the list again after every change, so the record would fill with the
  screen refreshing itself and the changes would be harder to find in it, not
  easier.
- **A `FileChooser` for the export destination.** Rejected for now: the path is
  typed into a field, which is drivable headless on Monocle and is the same thing
  the service refuses or accepts. A chooser is a better screen and it is not a
  different decision; the refusals it would produce are already worded.
- **Reading the configured `InactivityPeriod` back so the panel can show it.**
  Not done, and it is the one thing about this panel that reads as unfinished: no
  request answers what the deployment is currently configured with, so the
  Administrator sets a value without being shown the one in force. Adding a
  request that answers it is small and belongs to whoever needs it; it is
  recorded here rather than left for a reviewer to find.

## Consequences

- The CredentialStore gains a nullable `language` column (V006). Nothing in this
  build writes it — issue #13 owns the selector, the ResourceBundles and the rule
  that an Account's own preference applies once it has authenticated — so every
  Account lists as having said nothing, which is what a deployment that has never
  been asked looks like. The column is nullable rather than defaulted because an
  Account that has expressed no preference must not read as having chosen the
  language a migration happened to pick.
- `PolicyRefusal` now answers three things rather than two: the first-run wizard,
  the enrolment screen, and an Administrator creating an Account. The third can
  only ever be about a name, because the panel never chooses a password.
- The login screen grows one control, which is the whole of story 37: the same
  screen, and a checkbox that decides which Role the attempt asks for. Whether
  the Account holds it is the service's decision, and an Operator who ticks it is
  refused in the same words as a wrong password — telling the two apart would
  name the Role an Account holds.
