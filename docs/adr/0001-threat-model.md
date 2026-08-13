# Threat model: what an offline login can and cannot promise

A login system with no server cannot stop an attacker who can modify the
application binary or attach a debugger to a process running under their own
identity. Claiming otherwise produces security theatre, so this project states
its guarantee narrowly and defends it with mechanisms the operating system
enforces rather than with checks written in Java.

**The guarantee.** An operating-system account without elevated privileges
cannot create, modify or delete Accounts, cannot read any password hash, and
cannot recover a password at reasonable cost.

**Explicitly out of scope.** An attacker holding root or local Administrator
rights on the machine. Such an attacker can read the MachineKey and reach every
secret; at that point the ProtectedFeature is not the most valuable thing they
have compromised.

**Also explicitly out of scope: a compromised Administrator Account.** This is a
different attacker from the one above — not an operating-system administrator,
but whoever holds the password to this application's single Administrator
Account. That password yields the SecretVault. Its holder cannot read the Vault
file directly, being an unprivileged operating-system user, but they can create
an Operator, enrol it, authenticate as it and ask the service for secrets one at
a time. Refusing Vault operations to an Administrator Session does not prevent
this and was never capable of preventing it, because the refusal is scoped to the
Role of a Session and a new Session is one provisioning step away.

The consequence is worth stating plainly: **the Administrator password is as
valuable as the Vault it can reach.** The compensating control is the audit log,
which records the creation, the enrolment and the authentication under an HMAC
chain that cannot be edited or removed — detection, not prevention. See ADR-0005
for why closing this properly would require a provisioning ceremony this project
deliberately rejected.

## Consequences

- Every control that matters is enforced by a process the attacker cannot
  modify, or by file permissions. Obfuscation, binary signing and integrity
  self-checks are treated as cost imposed on an attacker, never as barriers, and
  are not relied upon.
- A patched client gains nothing: it can neither read the CredentialStore nor
  obtain a SessionToken without a correct password.
- While a Session is open, the SecretVault serves individual secrets into a
  process the Operator could debug. That exposure is accepted and bounded by
  serving one secret at a time rather than handing over the DataKey.
