# Two separate stores, and the Vault unlocks cryptographically

The CredentialStore and the SecretVault answer different questions, face
different attackers and change at different rates, so they are separate files
rather than tables in one database. The CredentialStore holds Accounts, hashes
and configuration. The SecretVault holds what a ProtectedFeature must keep
secret, such as credentials for other systems.

The Vault does not unlock because a boolean says authentication succeeded — that
check is exactly what a patched binary would remove. It unlocks because the
password the Operator just typed derives, through Argon2id with its own salt and
parameters, the key that unwraps the DataKey. The stored authentication hash is
never reused as key material.

## Consequences

- The DataKey is shared by all Operators, wrapped once per Operator and once more
  under the MachineKey. The MachineKey copy is what lets the AuthenticationService
  provision a new Operator, or rewrap after a password reset, without any Operator
  being present and without exposing the DataKey to the Administrator.
- An Operator who forgets their password loses nothing: the Administrator resets
  it and the service rewraps. No recovery key is needed, and none is issued.
- Individual secrets are encrypted under keys derived from the DataKey and
  decrypted only at the moment of use, so the plaintext window is milliseconds
  rather than the whole Session.
- Both stores are owned by the AuthenticationService, which also protects the
  Vault against destruction by any process running as the Operator. Every write
  therefore crosses the IPC boundary, which bounds the Vault to credentials and
  configuration rather than bulk data.
