# Lockout lives in the store, is timed by the wall clock, and says so

An Account that fails authentication often enough is refused for a while, so
that guessing at the login screen is not free. Three things about this project
decide how that is built, and the third one costs something worth naming out
loud.

The service does not run continuously. ADR-0002 has it starting on demand and
stopping after five idle minutes, so a counter held in memory is a counter an
attacker clears by waiting rather than by guessing correctly — story 89 says
exactly this. The state therefore lives in the CredentialStore, which is also
the file an Operator cannot read, delete or edit.

Nothing else in the system persists a countdown. A Session is timed against two
clocks, one of which cannot be moved (ADR-0009); a Lockout cannot use it,
because a monotonic reading is a count from an origin the process chose and
means nothing to the process that reads the store after a restart.

And telling a person they are locked out tells whoever is guessing that the name
they guessed is real. Story 34 wants a refusal that reveals nothing about
whether an Account exists; story 43 wants a locked Operator told they are locked
and for how long. Both cannot hold at the margin.

**The decision.**

- The failure count and the moment the refusal ends are two columns on the
  Account, written the moment they change and flushed by `synchronous = FULL`.
  A Lockout survives a restart of the AuthenticationService, and outlives the
  process that decided it.
- Nothing is remembered about a name no Account holds. Refusals against one are
  counted nowhere and it is never locked out, because the alternative is a row
  in the privileged store for every string ever typed at the login screen — and
  one of those strings is eventually somebody's password typed into the wrong
  box, which is the same reasoning story 77 applies to the audit log.
- The refusal is decided **after** the Argon2id verification, not instead of it,
  so that a locked Account, a wrong password and an absent Account all cost the
  same. Skipping the work would save this service nothing worth having: every
  attempt already costs one verification whatever name it names, which is what
  the absent-Account branch is for.
- A Lockout is timed by the wall clock, and **never outlasts the length it was
  configured with**. A Lockout that claims to end further away than that is read
  as over: the machine's clock was set backwards since it was written.
- The number of failures and the length of the Lockout are configuration in the
  CredentialStore, read again on every decision, as the InactivityPeriod is.
- A correct password offered in the wrong Role counts as a failure like any
  other. An Account that could never be locked out would be one an attacker
  picks out of the account list by failing at it all afternoon — and the one
  Account whose Role is guessable is the Administrator's.
- Entering a Lockout and clearing one are each an AuthenticationEvent, recorded
  against the Account. Only the single Administrator can clear one, so recording
  who did it would be recording the same name every time.

**What the refusal costs, stated plainly.** `LOCKED_OUT` is the one answer this
service gives that says something about an Account. Reaching it costs an
attacker one Argon2id verification per guess, five guesses per name, the Account
they were after locked for a quarter of an hour, and a line in the audit log
saying so. What they buy is the knowledge that a name they already guessed
correctly exists. The trade was made deliberately, in favour of the person
standing at a login screen that would otherwise refuse them silently for fifteen
minutes with no way of knowing why.

## Considered options

- **The count in memory.** Rejected by story 89 and by the service's own
  lifecycle: it stops after five idle minutes, so waiting would clear it. This
  was the one thing the ticket named as the important property.
- **A file of its own beside the store.** Rejected: it buys nothing the store
  does not already give — the same directory, the same owner, the same mode —
  and it adds a second thing to keep consistent with the Accounts it is about.
- **Counting failures against the name that was typed, so that an absent
  Account locks out identically and the answer leaks nothing.** Rejected. It
  removes the leak honestly, and it pays for it by writing every string typed at
  the login screen into the privileged store, unbounded, including the passwords
  people type into the name box. A guessing attack would also grow that table on
  purpose.
- **Refusing a locked Account without doing the hashing.** Rejected: the
  refusal would come back sooner than every other refusal, and a stopwatch would
  then find the locked Accounts even if the message said nothing. The work is
  not a cost worth saving here, because the attempt was going to cost one
  verification anyway.
- **Never telling the client, and letting a locked Account read as a wrong
  password.** Rejected by story 43. It closes the leak completely and leaves a
  person retyping a password that is correct, for a quarter of an hour, with the
  screen insisting it is wrong.
- **Locking permanently until an Administrator clears it.** Rejected: on a
  single-machine deployment with one Administrator, that turns a mistyped
  password into an outage, and it hands anyone who can reach the login screen a
  way to take every Operator offline.
- **Reading a Lockout that outlasts its configured length as still running.**
  Rejected: a clock error would refuse someone for a year. Setting the machine's
  clock takes the privileges of a MachineAdministrator, and whoever holds those
  can rewrite the store directly, so treating it as over costs nothing that was
  not already gone.

## Consequences

- A Lockout is testable in milliseconds, as expiry is: the clocks are an
  interface, so the suite moves them rather than waiting a quarter of an hour.
- The wire grows one refusal that carries data — how long the Lockout has left —
  and the record that carries it refuses to be built any other way: a Lockout
  always says how long, and nothing else ever does.
- An Administrator clearing a Lockout for a name nobody holds is told so, rather
  than told it worked. The Administrator is the only Account that can ask, and a
  mistyped name that answered `Ok` would leave the colleague locked out and
  nobody looking for them.
- Raising the configured length does not extend the Lockouts already written;
  lowering it ends them early, because none may outlast the length configured
  now. Both are the same rule, and both are what an Administrator changing a
  setting would expect of it.
- Nothing here slows an attacker who has taken the store and is guessing against
  a hash offline. That is Argon2id's job, at the parameters ADR-0002 pins, and
  reading a Lockout as protection against it would be reading it as a strength
  it does not have.
