# Session expiry is decided when someone asks, against two clocks

A Session must end when the Operator walks away. Three things follow from where
this project put its security boundary, and together they decide the mechanism.

The client cannot be trusted to end it. A patched one would simply not, so the
decision belongs to the AuthenticationService, and everything the client does
here is reporting: the SessionGuard says the Operator did something, and asks
whether the Session is still there. It holds no countdown of its own.

The service does not run continuously. ADR-0002 has it starting on demand and
stopping after five minutes without Sessions, so a thread of its own inside a
privileged process, waking up to sweep Sessions, is a thing that has to be
started, stopped, and reasoned about at both ends of that lifecycle.

And no single clock will do. `System.nanoTime()` cannot be moved by anyone, which
is exactly what expiry has to be measured against — but on Linux it excludes the
time the machine spent suspended, so a laptop closed for an hour would come back
with its countdown where it left it. `Instant.now()` counts that hour and can be
set to any value by whoever can change the machine's time.

**The decision.** Expiry is evaluated lazily, inside the request that touches the
Session, against both clocks:

- Every request carrying a SessionToken first asks whether the live Session has
  run out. There is no timer and no sweep thread.
- If the two clocks disagree by more than a tolerance, the Session ends as
  `CLOCK_JUMPED`. The service can no longer say how long it sat idle, and a
  Session it cannot account for is one it ends.
- Otherwise the idle time is the **longer** of the two measures, and the Session
  ends as `INACTIVITY` when that reaches the configured InactivityPeriod.
- The answer to every report and every question carries how long is left, and the
  guard schedules its next question from that. It therefore asks once per
  countdown, at the moment the service said the time would be up, rather than
  polling.
- A client that stops asking expires. A client that dies takes its connection
  with it, and a Session is bound to the connection it was granted on, so the
  kernel ends it — no heartbeat, in either direction.

The tolerance is one minute, and it is worth being precise about what it is for.
It is **not** what makes moving the clock useless: taking the longer of the two
measures is, because a clock set backwards shortens the wall-clock measure and
leaves the monotonic one untouched. The tolerance is only where the service stops
treating a disagreement as ordinary drift, set above what a time synchronisation
corrects and below anything a person would call walking away.

## Considered options

- **A timer inside the service.** Rejected: it puts a scheduler in the privileged
  process for something that has no observer when it fires. Nothing acts on an
  expired Session until a client asks, and the client is going to ask anyway.
- **A heartbeat from the client.** Rejected twice over. As a way to notice a dead
  client it is unnecessary — the socket closing already says so, exactly and
  immediately — and it would replace a certainty with a timeout. As a way to keep
  a Session alive it inverts the design: a client that can keep a Session alive by
  saying so is a client a patch can keep one alive forever with.
- **The wall clock alone.** Rejected: setting the machine's clock back would
  extend a Session, and that is a story (53) rather than an oversight.
- **The monotonic clock alone.** Rejected: it would not count a suspend, so
  closing the lid and coming back tomorrow would resume the same Session.
- **`CLOCK_BOOTTIME`, which counts suspend and cannot be set.** Rejected because a
  JVM cannot read it without native code, and because it is Linux's alone — the
  same two clocks have to answer on both target platforms.

## Consequences

- A machine resumed from a suspend longer than the tolerance reports
  `CLOCK_JUMPED` rather than `INACTIVITY`. The two are genuinely
  indistinguishable from inside a JVM, and the Session ends either way, so the
  wording a person reads names both possibilities rather than guessing between
  them.
- Expiry is testable in milliseconds. The clocks are an interface the service is
  handed, so the suite moves them instead of waiting, and moves them
  independently to describe a machine that was suspended or a clock that was set.
- Switching expiry off switches off both rules. A kiosk Session is not ended by a
  clock jump either: there is no idle time being accounted for, and a kiosk that
  logged itself out because the machine's time was corrected would be a kiosk
  nobody could keep running.
- The guard's questions cost one round trip per countdown, and its reports are
  coalesced to at most one every twenty seconds. What that costs is precision
  nobody can perceive against an inactivity period measured in minutes.
- Because the service holds one Session, the registry is one slot and one lock,
  held long enough to read two clocks. The lock is not the service's own, so the
  connection-closed listener never queues behind an Argon2id hash.
