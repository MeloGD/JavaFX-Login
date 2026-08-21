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
Administrator does not hold access to the SecretVault and cannot obtain it
without leaving a record: every Vault operation from an Administrator Session is
refused by the AuthenticationService, so the way to a secret is to create an
Operator and enrol it, and both of those are AuthenticationEvents. This is least
privilege and not a barrier — see ADR-0005, and never write that secrets are
protected _from_ the Administrator.
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

**AdministrationPanel**:
The screens an Administrator runs a deployment from: the Accounts it holds, the
configuration that applies to all of them, and the copy of the
AuthenticationEvents. It is reached from the same screen as the ProtectedFeature
and one control apart — the attempt asks for the Administrator Role rather than
the Operator one — and every request behind it is refused by the
AuthenticationService unless the Session naming it is an Administrator's, so
drawing the window is never what grants it. There is nowhere on it to type an
Account's password, and nothing it shows once can be asked for again. The one
password box it does hold is a Backup's: it seals a file, nothing verifies it
against anything, and knowing it admits nobody.
_Avoid_: admin screen, management console, dashboard, settings

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
the Operator is told that it happened and when — on every successful login until
they say they have read it, because a notice that was sent is not a notice that
arrived.
The Administrator's own password is not subject to it: that one is chosen at the
FirstRunWizard by whoever will use it, and there is nobody to hand a secret to.
_Avoid_: forgot password, recovery, password change

**SecondFactor**:
A second proof of identity, beyond the password, that an Account could be asked
for. Deferred from v1: a seam and a reserved column in the CredentialStore exist
so that adding one later is a change of behaviour rather than of shape. Nothing
implements it, and the AdministrationPanel shows the option visibly disabled.
_Avoid_: 2FA, MFA, TOTP, one-time code

**PasswordStrength**:
A coarse estimate — weak, acceptable or strong — of how resistant an Account's
password is to guessing. Only the coarse band is ever recorded.
_Avoid_: score, entropy, complexity rating

**LanguagePreference**:
The language the person using an Account reads the interface in, or nothing at
all where they have said nothing and the machine's own locale answers for them.
It is held in the CredentialStore beside the Account, listed and chosen in the
AdministrationPanel, and answered by the AuthenticationService on the admission
that proves somebody holds the Account — which is the earliest moment there is
anybody to answer about. It takes effect at the next admission and never at the
moment it is recorded: it is a fact about an Account, not a change to whatever
screen is open.
_Avoid_: locale, i18n setting, translation, language

**InterfaceLanguage**:
The language a screen is actually drawn in, which is not the same thing as a
LanguagePreference. Before anybody has authenticated it is the machine's own, or
whatever the selector on the login screen was set to; after an admission it is
the LanguagePreference of the Account admitted, and where that Account has said
nothing it stays as it was. It is the client's throughout: the
AuthenticationService names things and never words them, so no bundle, no
language list and no sentence exists inside the privileged process, and adding a
language is a properties file in login-ui — see ADR-0014.
_Avoid_: locale, i18n, translation, language file

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

**IdleShutdown**:
The AuthenticationService stopping by itself once nobody is using it, five
minutes after the last client has gone. It is what keeps a privileged JVM from
sitting idle between logins, and it is why every counter this system keeps —
a Lockout, an Enrolment, the AuthenticationEvents — is on disk rather than in
memory: state that did not survive it would be state an attacker clears by
waiting. The service is in use while a Session is live *or* a client is still
connected, because a connection with no Session behind it is a person at the
login window who has not typed a password yet.
_Avoid_: timeout, auto-shutdown, service expiry, idle timer

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

**Backup**:
An encrypted copy of the Accounts and the configuration of a deployment, written to
one file an Administrator names and sealed under a password they type at the moment
of the export — not an Account's password and not checked against one. It restores
on any machine, which ADR-0006 chose over binding it to the one that wrote it, so it
carries nothing that machine keeps: no SecretVault, no MachineKey, no
AuthenticationEvents, and no Enrolment anybody is halfway through — the Account
travels, the secret addressed to a machine that no longer exists does not, and a
restore puts such an Account back waiting for an Administrator to issue one.
Restoring replaces the store wholesale and never merges, refuses anything it cannot
fully read before writing a row, drops every wrapped copy of the DataKey the
SecretVault held, and ends the Session that asked. What is missing from a restored
deployment, and why, is ADR-0015.
_Avoid_: dump, snapshot, archive, sync

**SecretVault**:
The named store of secrets a ProtectedFeature needs but must not hold in the
clear, such as credentials for other systems. Its own file beside the
CredentialStore, owned by the AuthenticationService. Secrets are served one at a
time, only to an Operator, and only for as long as their Session lasts — each is
decrypted at the moment it is asked for, so the plaintext window is one request
rather than the whole Session.
_Avoid_: keystore, secret store, credential cache

**UnlockedVault**:
The SecretVault while one Session holds it open. It is what an Operator's
password produced when they logged in, it answers for one named secret at a time,
and it is destroyed by whichever of the four things that end a Session gets there
first. There is no way to obtain one but to offer a password that unwraps the
DataKey, which is why nothing about reaching a secret is a check that could be
patched out.
_Avoid_: vault session, open vault, unlocked keystore

**DataKey**:
The single key that encrypts the SecretVault, shared by every Operator and never
stored unwrapped. It is wrapped once per Operator, under a key their password
derives through Argon2id with a salt and parameters of the Vault's own — the
stored authentication hash is never key material — and once more under the
MachineKey. Nothing outside the vault package can name it, so no API hands it out.
_Avoid_: master key, vault key

**KeyEncryptionKey**:
The thirty-two bytes a password derives, and the only thing that unwraps an
Operator's copy of the DataKey. Made through Argon2id with a salt and cost
parameters recorded beside the wrap it opens — never read from the stored
authentication hash, which is never key material. It exists for one request and is
overwritten before that request answers.
_Avoid_: KEK, derived key, password key

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
