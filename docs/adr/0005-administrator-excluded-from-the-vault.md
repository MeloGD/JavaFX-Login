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
- There is exactly one Administrator, so no Administrator can be deleted without
  another taking its place, and administrative access cannot be silently widened.
