# The Administrator never chooses a password, and hands over a secret instead

An Operator is created by somebody else. Whoever creates them decides what they
are called and what Role they hold; ASVS 5.0 §6.4.6 says they must not decide
what the Account authenticates with. This ADR records how that is built, what it
buys, and — as ADR-0005 already insists — how little it buys against the attacker
people assume it stops.

ADR-0005 is the honest statement and it has not changed: **this does not defend
the SecretVault against a compromised Administrator.** Whoever holds the
Administrator password creates an Operator, enrols it themselves, and
authenticates as it. That is two requests. What enrolment removes is the quieter
thing next to it — an Administrator setting the password of an Account somebody
else is already using, handing it back, and going on knowing a credential that
stays in use indefinitely. After this, taking over an existing Account is
one-shot and noisy: the old password stops working immediately and its holder is
told at their next login.

**The decision.**

- `CreateAccount` carries a name and a Role and **no password**. What comes back
  is an `EnrolmentSecret`: 128 bits from a `SecureRandom`, written in Crockford's
  base 32, shown once. `CompleteEnrolment` carries the name, the secret and a
  password chosen by whoever holds it, and **no SessionToken** — the person
  sending it has not authenticated and cannot, because the Account they are
  enrolling has no password until it returns.
- The store keeps a **SHA-256 of the secret and never the secret**. It is not
  written to an AuthenticationEvent either: the record says an enrolment was
  issued and against which Account, which is what somebody reviewing it needs.
- An Account holds **a password or an outstanding enrolment, never both and never
  neither**, enforced by a `CHECK` in the schema rather than only by the code
  above it. `InitiateReset` therefore takes the old hash away in the same
  statement that writes the new secret.
- The secret **expires** after a configured lifetime — three days as shipped —
  read again on every decision like every other setting here, and it is
  **consumed by the enrolment it completes** rather than by an attempt at one. A
  password the AccountPolicy refuses leaves the secret good, or somebody who
  chose a password one character short would have to go back for another.
- Authenticating against an Account awaiting enrolment answers
  `ENROLMENT_REQUIRED`, which is the second refusal in this system that says
  something about an Account. Story 30 asks for it.
- A wrong secret **counts towards the Lockout** like a wrong password. Being sent
  to the enrolment screen **does not**.
- Re-issuing a lost or expired secret is `InitiateReset` again: for an Account
  awaiting enrolment there is simply no password to take away, and nothing the
  Operator is owed being told about.
- The single Administrator is outside all of it. Its password is chosen at the
  FirstRunWizard by whoever will use it, and both `CreateAccount` with the
  Administrator Role and `InitiateReset` against the Administrator are refused.

## Why a fast hash, and not Argon2id

Argon2id is slow because a password is something a person chose, and a person's
choices are guessable at a rate a work factor is worth paying to reduce. An
enrolment secret is 128 bits the service generated. There is no distribution to
guess against and nothing a work factor buys: at one attempt per nanosecond for
the age of the universe, the search is not started. ASVS 5.0 allows a fast
cryptographic hash for exactly this case, and paying Argon2id's hundred
milliseconds here would buy a slower enrolment screen and nothing else.

The comparison is constant-time all the same. It cannot matter — the digests are
compared, not the secrets — and there is no reason to hand a stopwatch even that.

## What `ENROLMENT_REQUIRED` costs, stated plainly

It names a name as a real Account and as one nobody has claimed yet. That is more
than `AUTH_FAILED` says and less than `LOCKED_OUT` says, and it is bought for the
price of one Argon2id verification, because the refusal is decided **after** the
verification for the reason ADR-0010 gives about a Lockout: a refusal that came
back in no time at all would name the Account with a stopwatch before the message
named it in words.

What an attacker does with it is offer secrets against that name, which is 128
bits against a store that counts every wrong one towards the same Lockout a wrong
password earns. The alternative is a person who has been handed a code standing
at a login screen that tells them their password is wrong, which is a screen that
will never let them in and never say why.

## Considered options

- **Let the Administrator set an initial password and force a change at first
  login.** Rejected: it is the arrangement ASVS §6.4.6 exists to name. The
  Administrator knows a working credential, and "force a change" is a rule the
  client would have to keep — which is exactly the kind of rule ADR-0002 refuses
  to put in the client.
- **Deliver the secret by email or SMS.** Rejected by the first sentence of
  `CONTEXT.md`: there is no network. Out-of-band here means across a desk.
- **A shorter secret, so that it is easier to type.** Rejected: below 128 bits it
  becomes a thing worth guessing, and then it needs a slow hash, rate limiting of
  its own, and a conversation about how many characters are enough. Crockford's
  alphabet and groups of four cost four extra characters and remove the question.
- **Argon2id over the secret.** Rejected above. It is defensible and it is
  cargo — the property that makes Argon2id necessary for a password is precisely
  the property this secret does not have.
- **Storing the moment the secret expires rather than the moment it was issued.**
  Rejected for consistency with every other setting here: the lifetime is
  configuration and is read again on every decision, so an Administrator who
  shortens it shortens the secrets already in somebody's pocket. Storing the
  expiry would freeze the old policy into rows written before the change.
- **Not counting a wrong secret towards the Lockout**, so that nobody can stall
  an enrolment by guessing at it. Rejected: the enrolment screen is the one place
  a credential for an Account awaiting enrolment can be offered, so leaving it
  uncounted would make waiting for enrolment the single state in which guessing
  at this system is free. The cost is that somebody who guesses a name can delay
  that Account's enrolment by a quarter of an hour, which an Administrator clears
  and which they can already do to every other Account by typing wrong passwords.
- **Counting `ENROLMENT_REQUIRED` towards the Lockout as well.** Rejected in the
  other direction: there was no password to be wrong about, and whoever guessed
  the name of a new Operator could otherwise lock them out of their own enrolment
  before they ever reached the screen.
- **Granting a Session at the end of a successful enrolment.** Rejected: it saves
  one typing of a password that has just been chosen and has never been tested,
  and it makes `CompleteEnrolment` a way to obtain a Session without
  authenticating. Typing it once is the first proof that it can be typed.

## Consequences

- The CredentialStore's `accounts` table is rewritten by V005, because
  `password_hash` has been `NOT NULL` since V001 and an Account awaiting
  enrolment has no password — not an empty one, not a placeholder, and above all
  not a hash of something the Administrator picked.
- An Account awaiting enrolment reads as the weakest PasswordStrength band until
  somebody chooses a password. That is V002's rule rather than a measurement: the
  band of a password nobody has chosen must not read as a strong one.
- The audit log gains the Account changes it was missing —
  `ACCOUNT_CREATED`, `ENROLMENT_SECRET_ISSUED`, `PASSWORD_RESET_INITIATED`,
  `ENROLMENT_COMPLETED`, `ENROLMENT_FAILED` and
  `AUTHENTICATION_REFUSED_ENROLMENT_REQUIRED` — and story 73's set is complete
  but for the SecretVault's own.
- Rewrapping the DataKey when an Operator completes an enrolment (story 61) is
  not here. It belongs to the SecretVault's ticket, which is where the DataKey
  first exists; the seam it needs is the single place this build writes a
  password an Operator chose.
- There is no screen for the Administrator's half of this. Creating an Account
  and initiating a reset are administration-panel work, for the same reason
  clearing a Lockout and exporting the record have no screen yet.
