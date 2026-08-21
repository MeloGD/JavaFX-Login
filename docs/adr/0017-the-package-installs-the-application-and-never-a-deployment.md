# The package installs the application and never a deployment

There are two things on an installed machine and they belong to different people.
The **application** — a launcher, a trimmed runtime, the jars, two unit files — is the
package's, put there by `dpkg` and taken away by it. The **deployment** is everything in
`/var/lib/javafx-login`: the Accounts and their password hashes, the SecretVault, the
configuration and the AuthenticationEvents. Nothing in the package made any of that, and
this decision is that nothing in the package may make it or take it away either.

A first-run wizard is what brings a deployment into existence, once somebody has logged
in and said who the Administrator is. That is ADR-0008 and ADR-0012, and both of them
would be worth nothing if an installer could write the same file: a `postinst` that
created a CredentialStore would be creating a deployment on a machine nobody has yet
logged into, at a moment when the only witness is `apt`.

Removal is the same argument pointing the other way, and the stakes are higher because
the mistake is not recoverable. `apt remove javafx-login` therefore takes the application
and leaves the deployment exactly where it is. A reinstall — after an upgrade that went
wrong, a machine being rebuilt, an administrator undoing something — picks it up again
and everybody logs in with the password they had. `apt purge` is the other word, and dpkg
only ever passes it when somebody asked for it by name.

## Considered options

- **Remove the data with the package.** Rejected on the strength of what the data is.
  Everything else a package removes can be recreated by installing it again; this cannot
  be recreated by anything. An uninstall that silently emptied a password store would be
  the worst behaviour this product could have, and it would be the default behaviour.
- **Ask, with a `debconf` prompt.** Rejected. The question would be asked in the middle of
  an `apt` run, most of which are unattended, and the answer that gets typed at a prompt
  nobody expected is whichever one ends it. A word on the command line — `purge` — is a
  decision somebody made before starting.
- **Refuse to purge while Accounts exist.** Rejected: it makes the destructive path
  something to work around, and a person working around it is doing `rm -rf` by hand with
  no message in front of them.

## Consequences

- The purge says what it is destroying while it is destroying it: every Account and its
  password, the SecretVault and every secret in it, the configuration, and the record of
  every authentication ever attempted — and that a Backup exported earlier is the only
  copy that survives. By then that sentence is the last record of what was there.
- The dedicated group goes with the purge and not with the removal. Membership of it means
  "may reach the AuthenticationService", and a group whose gid is later handed to another
  group would quietly give that meaning to a different set of people.
- **An upgrade reasserts the deployment's ownership and mode rather than assuming them.**
  It is `0700` and root-owned on every `configure`, because an upgrade that loosened the
  directory holding every password hash would take away the only real security property
  this product has, and everything would go on working. This is the reason the package
  runs the same `install.sh` a developer runs by hand rather than repeating it: one
  implementation of what a machine needs, and one place for a mistake in it to be.
- **Schema migrations run in the `postinst`, not at the next activation.** The package may
  bring a deployment forward even though it may not create one, and the moment to do it is
  while somebody is watching. Under socket activation nobody is watching afterwards: a
  service that cannot open its files looks exactly like a service that has not been
  connected to yet, so a failed migration would reach its owner as "the
  AuthenticationService is not running", long after the installation said it succeeded. A
  store from a later build than the package stops the installation, naming both versions.
- A machine that has had this product removed still has a deployment on it, `0700` and
  root-owned, that nothing on the machine will ever open again unless the package comes
  back. That is the intended state, and `apt purge` is how somebody says otherwise.
