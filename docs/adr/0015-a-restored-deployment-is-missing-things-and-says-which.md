# A restored deployment is missing things, and says which

ADR-0006 settled the hard part — a backup restores on any machine, and is protected
by a password and Argon2id rather than by anything the machine that wrote it knows.
It left four questions that only turn up once somebody actually restores one.

**The Account travels; the Enrolment does not.** Resurrecting the Enrolment was never
an option: the secret is in somebody's pocket, addressed to a machine that no longer
exists, and a replacement that accepted it would be honouring an invitation nobody
reissued. Dropping the *Account* along with it was the tempting shortcut and is
wrong — an Operator whose password an Administrator took away yesterday is not
transient. They are a person with a name, a Role and a language, halfway through
getting a new password, and a Backup that dropped them would be losing somebody to
the timing of a reset.

So the hash is absent in the Backup where it was absent in the store. The schema
refuses an Account with neither a password nor an outstanding enrolment, so a restore
writes such an Account waiting on a secret this machine generated and told nobody:
128 bits behind a hash that no offer can match. It is the honest shape of "waiting
for the Administrator to issue one", and the Administrator issues a real one from the
panel — the conversation they were going to have anyway, because the old machine's
secret was never going to work.

**The SecretVault's secrets stay; every wrap in it goes.** The Vault is excluded from
the Backup by ADR-0006, and the wrapped copies of the DataKey are inside it. A
restored Operator therefore logs in with the password they always had and is refused
a secret with `NO_VAULT_ACCESS` until an Administrator resets them and they enrol
again.

Destroying the *secrets* to make that symmetrical was rejected: they are covered by
no Backup at all, and an import that quietly deleted them would be the one
irreversible thing here nobody was warned about. Destroying the *wraps* is not
optional, though, and an earlier draft of this got it wrong. A wrap is keyed by an
Account name, and after a wholesale replace every name in that table belongs to
somebody who no longer exists — so a restored Account that happens to be called what
a local Operator was called would inherit that Operator's way into this machine's
Vault. Nobody decided to give it to them. Import therefore drops every wrap, which
also makes "reset and enrol again" true uniformly rather than depending on who was
called what. The secrets survive because the DataKey is wrapped under the MachineKey
as well, and the MachineKey is not keyed by anybody's name.

**The schema version travels, and an import refuses anything but this build's.** A
Backup written before or after this schema is not migrated on the way in. Rows
shaped for other columns are exactly the corruption a backup exists to avoid, and
the remedy — the build that wrote the file — is one an Administrator can actually
act on. The cost is real and is accepted: an old Backup needs an old build to
restore it.

**An import that names no Administrator is refused before anything is written.**
There is no way back from one. The FirstRunWizard is offered only while no
Administrator exists, and this store would have had one until the import replaced it
with Accounts that cannot create another.

## Consequences

- Import is one transaction over an emptied store: it is the Backup's, or it is
  exactly what it was, and never half of each. Every refusal above happens before
  the first row is written.
- Import ends the Session that asked for it. That Session was granted to an Account
  in a store that no longer exists, and carrying it on would be the only moment in
  this system where a Session outlives what it names. The client says what was
  restored and hands the person back to the login screen.
- The record of AuthenticationEvents is not in a Backup and stays on the machine
  that wrote it, along with the key its EventChain is computed under. So the entries
  either side of a `BACKUP_IMPORTED` are the deployment that used to be there, and
  that line is where it stopped being.
- A Backup opened with the wrong password and one somebody damaged are one refusal.
  GCM's tag fails identically for both, and a privileged process that told them apart
  would be telling whoever is guessing which guess was closest.
- The panel grows one password box, and it is not an Account's. It seals a file,
  nothing verifies it against anything, and knowing it admits nobody — see
  `AdministrationPanel` in CONTEXT.md, which now says so explicitly.
- **`blocked-account-names.txt` is not in a Backup.** It is configuration by any
  honest reading, and it is deliberately left out: it is a file a deployment's
  installer writes beside the store, not a row the `AuthenticationService` owns, and
  it travels with the installation rather than with the Accounts. A restored machine
  gets whatever its own installer put there. Named here so that the omission is a
  decision rather than an oversight somebody rediscovers.
- A Lockout survives a restore, carried as the wall-clock instant it was written as.
  That is ADR-0010 read consistently — a Lockout is a fact in the store rather than
  in memory — and it means a restore is not a way to end one. The cost is that an
  instant from a machine that died is compared against the clock of one that did not;
  the `Lockouts` component already reads a refusal that claims more time than the
  policy allows as expired, so the worst case is a Lockout that ends early.
