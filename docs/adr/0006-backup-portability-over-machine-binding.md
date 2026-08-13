# Backups stay portable; keys are not bound to the machine

Binding key material to Windows DPAPI or the GNOME keyring would mean that files
copied to another machine are useless even to someone who knows the password.
It would also mean a restored backup is useless on a replacement machine, which
is the one situation a backup exists for. We chose portability.

The protection that remains is not weak: without an Operator's password the
copied files yield nothing, and Argon2id makes guessing it expensive. DPAPI
would have added a second obstacle to an attacker who already lacks the first.

## Consequences

- Backups are exported encrypted under a password the Administrator types at the
  time, cover Accounts and configuration but not the SecretVault, and restore on
  any machine.
- Import replaces the store wholesale and never merges: merging Accounts from two
  origins produces states no one can reason about.
- Any future machine binding must be introduced as a second layer that is
  explicitly excluded from backups, not as a change to how the DataKey is
  wrapped.
