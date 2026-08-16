# JavaFX Login

A reusable, offline login template that gates access to a feature owned by a host
product. It has no network dependencies: every account, secret and audit record
lives on the machine where the application is installed.

## Language

### Identity and roles

**Account**:
A named identity that can authenticate against this system. Every Account holds
exactly one Role.
_Avoid_: user, profile

**Administrator**:
The single Account that manages other Accounts and application configuration. An
Administrator can never read secrets held by the Vault.
_Avoid_: admin user, superuser, root

**Operator**:
An Account allowed to reach the ProtectedFeature. A deployment holds one or more
Operators.
_Avoid_: user, regular user, standard account

**Peer**:
The process at the other end of a connection to the AuthenticationService, as the
operating system names it — never as it describes itself. The kernel attaches the
name when the connection is accepted, so nothing a client sends takes part in it.
_Avoid_: client, caller, connecting user

**MachineAdministrator**:
An operating-system account that administers the machine this product is
installed on: root, or a member of a group the operating system treats as
administrative. It is not an Account of this system, holds no Role, and can
authenticate against nothing here. It matters exactly once — the FirstRunWizard
is refused unless the connecting peer runs as one.
_Avoid_: root, sudoer, elevated user, local admin

**FirstRunWizard**:
The screen a person sees the very first time the product runs, and the only way
the single Administrator ever comes into existence. It is offered while no
Administrator exists, and accepted only when the peer is also a
MachineAdministrator. It prefills and suggests nothing, and it issues no recovery
key.
_Avoid_: setup screen, onboarding, installer, registration

**Role**:
The single capability set attached to an Account: either Administrator or
Operator. Roles are mutually exclusive and an Account never holds both.

**Enrolment**:
The state of an Account that has no password, and the act of giving it one. Every
Operator begins here: the Administrator creates the Account and is handed an
EnrolmentSecret rather than choosing a password, and the person who will use the
Account turns that secret into a password nobody else has ever known. An Account
holds a password or an outstanding Enrolment, never both and never neither, and
the CredentialStore refuses any row that says otherwise.
_Avoid_: activation, registration, onboarding, invite

**EnrolmentSecret**:
The 128 bits an Administrator hands over instead of a password. It is shown once,
kept only as a hash, never written to an AuthenticationEvent, expires, and is
consumed by the Enrolment it completes. It stands behind a fast hash rather than
Argon2id, because it was generated rather than chosen and there is nothing for a
work factor to buy.
_Avoid_: invite code, token, activation key, one-time password

**PasswordReset**:
An Administrator taking an Operator's password away and issuing an
EnrolmentSecret in its place. The old password stops working at once rather than
when the new one arrives, so a reset cannot be started and quietly abandoned, and
the Operator is told at their next successful login that it happened and when.
The Administrator's own password is not subject to it: that one is chosen at the
FirstRunWizard by whoever will use it, and there is nobody to hand a secret to.
_Avoid_: forgot password, recovery, password change

**SecondFactor**:
A second proof of identity, beyond the password, that an Account could be asked
for. Deferred from v1: a seam and a reserved column in the CredentialStore exist
so that adding one later is a change of behaviour rather than of shape. Nothing
implements it, and the administration UI shows the option visibly disabled.
_Avoid_: 2FA, MFA, TOTP, one-time code

**PasswordStrength**:
A coarse estimate — weak, acceptable or strong — of how resistant an Account's
password is to guessing. Only the coarse band is ever recorded.
_Avoid_: score, entropy, complexity rating

**AccountPolicy**:
The rules about what an Account name and a password are allowed to be, applied
inside the AuthenticationService so that a patched client cannot skip them. It
refuses or accepts; the PasswordStrength it reports alongside is for a person to
read and never a refusal.
_Avoid_: validation rules, password validator, complexity policy

**PolicyViolation**:
One rule an Account name or a password breaks, named rather than worded, so that
the client turns it into a sentence in the language the person reads. Every
refusal by the AccountPolicy carries every violation it found.
_Avoid_: validation error, error message

### Access and sessions

**AuthenticationService**:
The privileged component that owns every credential file and is the only party
that can verify a password. It is the security boundary of this system: nothing
outside it can read a password hash.
_Avoid_: daemon, server, backend, auth server

**Authenticator**:
The component inside the AuthenticationService that turns a password into a hash
and checks a password against one. It is named apart from the service on
purpose: the service is the privileged process, and letting one name cover both
would blur what the security boundary is.
_Avoid_: password checker, credential validator, auth helper

**Session**:
The period during which an authenticated Operator may reach the ProtectedFeature.
A Session ends on logout, on inactivity, when the machine's clock stops agreeing
with the clock that cannot be moved, or when the client that owns it disappears.
A machine holds at most one at a time: a second authentication while one is live
is refused, and the live one is kept.
_Avoid_: login, connection, sign-in

**SessionToken**:
The opaque value that identifies a Session to the AuthenticationService. It never
outlives the process that issued it.
_Avoid_: session id, ticket, cookie

**InactivityPeriod**:
How long a Session may go without Operator activity before the
AuthenticationService ends it, or that expiry is switched off — which is what a
kiosk deployment is. It is configuration, owned by the Administrator and held in
the CredentialStore, and it is read again on every decision rather than
remembered, so changing it changes what happens next.
_Avoid_: timeout, idle timeout, session length

**LoginGate**:
The entry point a host product calls to obtain a Session. It is the only part of
this system a host product is required to know about.
_Avoid_: login manager, auth facade

**SessionGuard**:
The component that reports Operator activity so an idle Session can expire. It
reports; it does not decide — expiry belongs to the AuthenticationService.
_Avoid_: idle timer, session watcher

**ProtectedFeature**:
The host product's functionality that sits behind the gate. This system knows it
only as a view it is handed, never by name or content.
_Avoid_: MainController, protected screen, main app

### Secret custody

**CredentialStore**:
The record of every Account, its password hash and the configuration of the
application. Only the AuthenticationService can read it.
_Avoid_: user database, account table, shadow file

**SecretVault**:
The named store of secrets a ProtectedFeature needs but must not hold in the
clear, such as credentials for other systems. Secrets are served one at a time,
only to an Operator, and only for as long as their Session lasts.
_Avoid_: keystore, secret store, credential cache

**DataKey**:
The single key that encrypts the SecretVault, shared by every Operator and never
stored unwrapped.
_Avoid_: master key, vault key

**MachineKey**:
The key, readable only by the AuthenticationService, that holds a second wrapped
copy of the DataKey. It is what lets an Administrator provision an Operator
without ever seeing the DataKey.
_Avoid_: host key, system key

### Auditing

**AuthenticationEvent**:
A recorded fact about access: an authentication attempt, a lockout, an Account
change, a configuration change, an export, or a Session ended because the
machine's clock moved. Events are written and never read back by the application.
Where there is no Account to name, one is recorded against a fixed placeholder
that no Account may be called — never against the string somebody typed.
_Avoid_: log line, audit entry, log record

**EventChain**:
The HMAC that links each AuthenticationEvent to the one before it, so that an
entry edited or removed in the middle breaks every entry after it. Its key lives
beside the CredentialStore and is readable only by the AuthenticationService,
which is what puts the record beyond the Administrator it may be about — and not
beyond a MachineAdministrator, who was never in the threat model. It is checked
when the record is exported, and nowhere else.
_Avoid_: signature, checksum, integrity hash

**AuthenticationEventExport**:
A copy of every AuthenticationEvent still kept, written to one file an
Administrator names and read with their own tools. It is the only way the record
is ever read: nothing hands an event back to the application. The copy is
owner-only, like everything else the AuthenticationService writes, and making one
is itself an AuthenticationEvent.
_Avoid_: log viewer, report, download

**Lockout**:
The state of an Account that has failed authentication often enough to be
temporarily refused. It is held in the CredentialStore rather than in memory, so
it survives restarts of the AuthenticationService and cannot be cleared by
waiting for one; only an Administrator, or the passing of its own time, ends it
early. It slows someone guessing at the login screen and does nothing about a
stolen hash, which is Argon2id's business.
_Avoid_: ban, throttle, block

**LockoutPolicy**:
How many failed authentications in a row make a Lockout, and how long that
Lockout lasts. Configuration, held beside the Accounts in the CredentialStore
and read again on every decision, so that changing it changes what happens next.
_Avoid_: retry limit, throttle settings, rate limit
