# The Administrator's exclusion from the Vault is least privilege, not a boundary

The Administrator manages Accounts and configuration, and is refused the
ProtectedFeature and every SecretVault operation. The AuthenticationService
enforces that refusal rather than the UI, because the UI is the part an attacker
can patch.

**This is not a security boundary, and must not be described as one.** The
refusal is scoped to the Role of a Session, so it is sidestepped by obtaining an
Operator Session rather than by attacking the refusal. Whoever holds the
Administrator password creates an Operator, enrols it, authenticates as it and
asks for secrets — a detour of seconds. ADR-0001 now names a compromised
Administrator Account as an attacker this system does not defend against, and
that is the honest position.

Provisioning without a ceremony and unconditional exclusion from the Vault cannot
both hold. Either creating an Operator requires an existing Operator to consent
to the rewrap, or an Administrator can mint Vault access. This project keeps the
no-ceremony choice.

## Considered options

- **Make the exclusion real, by removing the MachineKey copy of the DataKey** so
  that only an authenticated Operator can wrap it for a new Operator. Rejected:
  it genuinely closes the hole, but if every Operator loses their password the
  Vault becomes unrecoverable permanently, since no recovery key exists by design
  (ADR-0004). That trades a threat already out of scope for irreversible data
  loss on an offline machine.
- **Drop the exclusion entirely** and let the Administrator hold Operator-level
  Vault access. Rejected: it is a trivial simplification that costs the one real
  benefit below — an Administrator's access to secrets would become an ordinary,
  unremarkable login rather than something that leaves a distinctive trail.

## What it actually buys

- **Least privilege in ordinary operation.** The Administrator does not routinely
  hold Vault access, so day-to-day administration cannot touch secrets by
  accident or by habit.
- **An anomalous, recorded path instead of a silent one.** Reaching the Vault as
  an Administrator requires creating and enrolling an Account. Those events are
  written to the audit log under an HMAC chain that cannot be edited or removed,
  only withheld. This is detection, not prevention, and it is the entire security
  value of the decision.

## Consequences

- The refusal stays enforced by the service and not by the client. The point is
  precisely that a patched client cannot convert an Administrator Session into
  Vault access directly; forcing the attacker through provisioning is what
  produces the trail.
- Provisioning an Operator remains an ordinary administrative action rather than
  a ceremony requiring a second Operator, because the service rewraps the DataKey
  under the MachineKey on the Administrator's behalf without revealing it.
- Adopting ASVS 5.0 §6.4.6 — the Administrator initiates a password reset but
  never chooses the password — removes the worst variant, in which an
  Administrator quietly takes over an existing Operator's Account and hands it
  back. A reset now invalidates the old password immediately and the Operator is
  told at their next login, so that path is noisy and one-shot. It also means the
  Administrator never passively knows a credential that remains in use.
- Neither the documentation nor the UI may claim that secrets are protected from
  the Administrator. The accurate statement is that the Administrator does not
  hold Vault access and cannot obtain it without leaving a record.
- The exclusion holds only as far as ADR-0001 in the other direction too: an
  operating-system administrator can read the MachineKey, and is out of scope.
- There is exactly one Administrator, so no Administrator can be deleted without
  another taking its place, and administrative access cannot be silently widened.
