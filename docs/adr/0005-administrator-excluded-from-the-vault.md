# The Administrator is excluded from the Vault, and the service enforces it

The Administrator manages Accounts and configuration but must never reach the
ProtectedFeature or the secrets behind it. Enforcing that inside the UI would be
worthless — the UI is the part an attacker can patch. The AuthenticationService
refuses every Vault operation from a Session whose Role is Administrator, and an
Administrator, being an unprivileged operating-system user, cannot read the
MachineKey that would otherwise yield the DataKey.

The separation is therefore enforced by the operating system, not by convention.

## Consequences

- Provisioning an Operator is an ordinary administrative action rather than a
  ceremony requiring a second Operator to grant access, because the service
  rewraps the DataKey under the MachineKey on the Administrator's behalf without
  revealing it.
- The exclusion holds only as far as ADR-0001: an operating-system administrator
  can read the MachineKey, and is out of scope.
- **The refusal is scoped to the Role of a Session, so it is sidestepped by
  obtaining an Operator Session rather than by attacking the refusal itself.**
  An Administrator who can provision Operators can provision one for their own
  use and authenticate as it. This is not a defect in the check; it is the direct
  cost of the first consequence above. Provisioning without a ceremony and
  unconditional exclusion from the Vault cannot both hold: either creating an
  Operator requires an existing Operator to consent to the rewrap, or an
  Administrator can mint Vault access. This project keeps the no-ceremony choice,
  so **the honest claim is that the Administrator is excluded from the Vault by
  default and observably, not unconditionally.**
- Adopting ASVS 5.0 §6.4.6 — the Administrator initiates a password reset but
  never chooses the password — removes the worst variant, in which an
  Administrator sets an existing Operator's password, uses it, and leaves no
  trace the Operator would notice. Under enrolment, a reset invalidates the old
  password immediately and the Operator is told at their next login, so that path
  becomes noisy and one-shot. It also means the Administrator never passively
  knows a credential that remains in use.
- What remains is the Administrator creating a fresh Operator and enrolling it
  themselves, which breaks nothing for anyone else. The control against it is the
  audit log: creation, enrolment and authentication are all recorded, and the
  HMAC chain means entries cannot be edited or removed, only withheld. That is
  detection rather than prevention, and it is stated here so no future reader
  mistakes this ADR for a stronger guarantee than it makes.
- There is exactly one Administrator, so no Administrator can be deleted without
  another taking its place, and administrative access cannot be silently widened.
