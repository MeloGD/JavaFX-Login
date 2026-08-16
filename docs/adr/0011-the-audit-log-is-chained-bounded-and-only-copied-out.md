# The audit log is chained, bounded, and only ever copied out

The record of what happened carries more weight here than its size suggests.
ADR-0001 puts a compromised Administrator Account out of scope and names this
log as the compensating control; ADR-0005 rests its entire security value on the
same sentence. Both promise a record that "cannot be edited or removed, only
withheld". This is where that promise is either kept or quietly broken.

Three facts decide the shape of it. The service does not run continuously, so
nothing may be held in memory between events. The application must never read an
event back, so the record cannot be a queryable thing inside the process that is
being audited. And the record must never be able to stop an authentication, so
every failure to write is a failure to record and nothing more.

**The decision.**

- **One line per event, CSV, four fields: the chain value, the time with its
  offset, what happened, and who it was about.** The chain value comes first,
  which is the one surprising thing about the format. An Account name is the only
  field a person controls, so it is written last, and finding where the chain
  value ends never depends on parsing through it. For the same reason a control
  character in a name is written as a space: one event is one line, and an
  Account name does not get to decide how many entries there are.
- **Each entry is an HMAC-SHA-256 over the chain value before it and its own
  text**, under a 32-byte key made once and kept beside the CredentialStore at
  owner-only. Editing or removing an entry breaks every entry after it.
- **The chain value the next entry follows is read from the disk when the
  service starts.** It has to be: the service stops after five idle minutes, and
  a chain that began again on each start would break after every login, which is
  precisely what a tampered record looks like. Reading one field of one line is
  not reading the record back — no event ever enters the application.
- **The record rotates at a megabyte and keeps five files.** The chain runs
  across the rotation, so removing a whole file is as visible as removing a line
  — except for the oldest file still kept, whose predecessor was dropped by
  design and which is therefore taken on trust.
- **Every entry is written to a channel that is forced to the disk and closed.**
  Not buffered, and not merely handed to the operating system: the store already
  pays `synchronous = FULL` for the same reason.
- **A failure to record is swallowed.** A full disk, an unwritable file, an
  unreadable key — the event is lost, and the authentication, the Lockout or the
  configuration change goes ahead. An entry that was not written is also not
  chained onto, so a failed write leaves a gap rather than a break.
- **Where there is no Account to name, a fixed placeholder is written**, and the
  bundled blocklist refuses that placeholder as an Account name so nobody can be
  provisioned under it. It stands in for an attempt against a name nobody holds
  and for one refused before any name was looked up. The typed string never
  reaches the record, because the name box is where a password eventually gets
  typed (story 77).
- **A failed authentication says here why it failed** — wrong password, no such
  Account, wrong Role, locked out, machine busy — where the client is told only
  that it failed. Telling them apart at the login screen would name which
  Accounts exist. Whoever exports this file has already proved they administer
  the deployment.
- **The record leaves as a file and never as a response.** The Administrator
  names a destination; the service copies every entry still kept, oldest first,
  checking the chain as it goes, and answers with two numbers: how many entries,
  and whether the chain held. The copy is owner-only, like everything else the
  privileged process writes.
- **The export is recorded after the copy is made**, so the copy does not claim
  to contain the export that produced it. The next export shows this one.

**What the chain is worth, stated plainly.** It stops the Administrator — an
Account of this system, not an account of the machine — from editing what the
record says about them, because the key sits at `0600` in a directory only the
service's account can read. It stops nobody who holds root. That is not a gap in
the design; it is ADR-0001's stated boundary, and a record that claimed to
survive a MachineAdministrator would be claiming a strength no offline product
has. What is left after a compromise of the machine is the copy that was exported
before it.

## Considered options

- **Keeping the chain head in a file of its own beside the record.** Rejected. It
  would make truncating the tail detectable as well as editing the middle, and it
  costs a second forced write per event. The ticket and both ADRs say "only
  withheld" — losing the newest entries is exactly what withholding is, and it is
  already what a failed write does. A sidecar would only move the same problem to
  the last line of a smaller file.
- **Putting the chain value last, as a log line usually does.** Rejected: finding
  where it begins then means parsing back through a quoted field an Account
  chose, and the one field a person controls is the one no parser should have to
  be right about.
- **Signing entries with a private key so that even the service cannot rewrite
  history.** Rejected: on an offline single machine, the signer and the verifier
  are the same computer, so the private key would live next to the record it
  signs. It would buy the appearance of a stronger property and none of the
  property.
- **Holding the events in the CredentialStore.** Rejected. It is a file that has
  to be handed to somebody and read with their own tools, so it would need
  exporting to CSV anyway — and it would tie recording an event to a store that,
  when it cannot be written, must still not stop an authentication.
- **Returning the entries in the response and letting the client save them.**
  Rejected twice over: the transport caps a frame at a megabyte (ADR-0003), and a
  response carrying events is one small refactor away from the in-app viewer
  story 74 exists to prevent.
- **Making the exported copy readable by whoever asked for it.** Rejected: an
  export the logged-in operating-system account can read is the account list and
  the pattern of every login, handed to the person ADR-0002 keeps them from. The
  Administrator reads the copy with the privileges the service runs with, which
  is what "with your own tools" costs.
- **Recording every Session that ends.** Rejected: it fills the record with what
  going to lunch looks like. Only the clock-jump ending is anomalous, and it was
  already recorded.
- **Recording refused first-run attempts.** Rejected, and it is the one omission
  worth arguing with: there is nothing to record such an attempt against except
  the name that was typed, and story 77 keeps typed strings out of the record. A
  refusal recorded against the placeholder would say only that somebody tried,
  which is worth less than the rule it would bend.
- **Making the rotation bounds configuration.** Rejected: nothing in this build
  would change them, and a setting nobody writes is a constant with a lookup in
  front of it.

## Consequences

- The chain is verified at exactly one moment: when the record is exported. That
  is the moment it matters, and it means the check has a caller in production
  rather than only in the suite.
- The oldest entry still kept can be edited without detection, because what it
  followed has been rotated away. Everything after it cannot.
- A record with a gap in it and a record that has been edited are different
  answers: a gap leaves the chain sound, and an edit does not.
- Every authentication now writes to the disk twice — the store, for what the
  Account has failed, and the record. That cost falls on the branch with an
  Account and on the branch without one alike, so the equal-cost property
  ADR-0010 keeps for a refusal is not disturbed.
- The `AuthenticationEventType` set is now complete for everything this build can
  do. Enrolment and the SecretVault will each add their own, and each of those is
  an Account change or an access story 73 already names.
- Nothing in the shipped client exports anything. `ExportAuthenticationEvents` is
  complete at the service and reachable only by an Administrator Session, which
  the login screen does not issue — the administration panel is where a person
  meets it.
