package com.javafxlogin.ui.login;

import com.javafxlogin.core.ipc.ServiceReachability;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.Session;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.Parent;
import javafx.stage.Stage;

/**
 * The entry point a host product calls to obtain a Session. It is the only part of this system a
 * host product is required to know about.
 *
 * <p>Integrating with it is two lines:
 *
 * <pre>{@code
 * LoginGate.toService(socketPath).protect(stage, session -> myFeatureView());
 * }</pre>
 *
 * <p>The view arrives as a function rather than as a view, so that nothing behind the gate is built
 * until someone is admitted, and what it is handed is the {@link Session} that admitting them
 * produced — a host that has no use for it yet can ignore the argument, and one that later wants to
 * log out or reach a secret already has the thing that names the Session. What it returns is a
 * {@link Parent} and nothing of the host's, so that the gate knows nothing about the feature it
 * protects.
 *
 * <p>It is an interface because a test drives the windows against a fake one. That is the seam the
 * UI tests run at: no service, no socket and no crypto, so a broken window cannot arrive disguised
 * as a broken hash. What an implementation owes is the whole of a client's conversation with the
 * AuthenticationService — which window to open, how the first Account comes into existence, and who
 * is admitted — and everything else here is the same for every implementation.
 */
public interface LoginGate {

  /**
   * The gate a shipped product uses: every attempt crosses the socket to the AuthenticationService,
   * which is the only party that can verify a password.
   *
   * @param socketPath where the AuthenticationService listens
   */
  static LoginGate toService(Path socketPath) {
    return new ServiceLoginGate(socketPath);
  }

  /**
   * Offers a name and a password on behalf of someone asking to reach the ProtectedFeature.
   *
   * <p>Blocks: verifying a password is deliberately slow, so this must not be called on the
   * JavaFX application thread.
   *
   * @return the Session, or the refusal the service made. A refused authentication says only that
   *     it failed — a reason a client could read is a reason an attacker could read — and the one
   *     refusal that says more says nothing about any Account.
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all,
   *     which is not a refusal and must not be shown as one
   */
  Admission admit(String accountName, char[] password);

  /**
   * Offers a name and a password on behalf of somebody asking to administer this deployment.
   *
   * <p>Story 37: the same screen, one control apart. It is a separate method rather than a flag
   * because the two are separate questions — this one asks the service for a Session in the Role
   * that manages Accounts and configuration, and nothing about it reaches the ProtectedFeature or
   * the SecretVault. An Operator who asks it is refused, and refused over there, in the same words
   * as a wrong password.
   *
   * <p>Blocks: verifying a password is deliberately slow, so this must not be called on the JavaFX
   * application thread.
   *
   * @return the Session, or the refusal the service made, worded exactly as an Operator's would be
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  Admission administer(String accountName, char[] password);

  /**
   * Every Account this deployment holds, which is what the administration panel is drawn from.
   *
   * <p>The account list is the thing ADR-0002 keeps out of an unprivileged process's reach, so this
   * is the one request that hands any of it over — and the privileged process refuses it to a
   * Session that is not an Administrator's. What comes back carries no password material at all.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AccountListing accounts(Session session);

  /**
   * Creates an Operator, and is handed a one-time secret to give to the person who will use it.
   *
   * <p>The Administrator does not choose the password and is never told one: what comes back is an
   * EnrolmentSecret, shown once, which its holder turns into a password nobody else has ever known.
   * That is ASVS 5.0 §6.4.6, and it is why this method takes no password to pass on.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AccountProvisioned createOperator(Session session, String accountName);

  /**
   * Takes an Operator's password away and is handed an EnrolmentSecret to replace it.
   *
   * <p>The old password stops working at once rather than when the new one arrives, so a reset
   * cannot be started and quietly abandoned — and the Operator is told it happened at their next
   * admission. The same request re-issues a secret that was lost or has expired.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AccountProvisioned resetThePasswordOf(Session session, String accountName);

  /**
   * Deletes an Operator, and their wrapped copy of the DataKey with them.
   *
   * <p>What it costs is stated at the screen that asks for it, because nothing here can undo it:
   * the Account, whatever it has failed, any outstanding enrolment and the only copy of the DataKey
   * that this person's password opened all go. The secrets in the SecretVault stay where they are —
   * every other Operator has a copy of their own.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AdministrationOutcome deleteOperator(Session session, String accountName);

  /**
   * Forgets what an Account has failed, which is how a colleague who fat-fingered their password is
   * released before the Lockout runs out on its own.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AdministrationOutcome clearTheLockoutOf(Session session, String accountName);

  /**
   * Changes how long a Session may idle here, or switches expiry off entirely, which is what a
   * kiosk deployment is.
   *
   * <p>The service reads the setting again on every decision, so this changes what happens next
   * rather than what happens after a restart.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AdministrationOutcome useInactivityPeriod(Session session, InactivityPeriod period);

  /**
   * Records which language the person using an Account reads the interface in, or that they read
   * whatever the machine does.
   *
   * <p>It takes effect at that Account's next admission rather than now: which language somebody
   * reads is a fact about their Account, and the service answers it on the admission that proves
   * the Account is theirs. Nothing here says which languages exist — the service records the tag it
   * is given, and what this build can actually draw is the bundles it ships.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @param preference the language, or empty to have this Account follow whichever machine it is
   *     used on
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  AdministrationOutcome useLanguagePreference(
      Session session, String accountName, Optional<Locale> preference);

  /**
   * Copies the record of AuthenticationEvents to a file the Administrator names, which is the only
   * way that record is ever read.
   *
   * <p>Nothing hands an event back to the application: what comes back says how much was copied and
   * whether the chain still held, and the copy itself is written by the privileged process, owner
   * only, wherever it was told to. A destination it will not write to is a refusal and not a
   * failure.
   *
   * <p>Blocks: it crosses the socket, and the record is copied while it does. Must not be called on
   * the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  ExportOutcome exportAuthenticationEventsTo(Session session, Path destination);

  /**
   * Writes a Backup of the Accounts and the configuration of this deployment, sealed under a
   * password typed for it, to a file the Administrator names.
   *
   * <p>The password is not anybody's credential and is checked against nothing. It is what the file
   * is encrypted under and the whole of what protects it once it leaves the machine, because
   * ADR-0006 refused to bind a backup to the machine that wrote it — one that only restores where it
   * was made is useless on the day that machine dies.
   *
   * <p>What is in the file is every Account that holds a password and every configured setting. What
   * is not is the SecretVault, the keys this machine keeps, the record of AuthenticationEvents, and
   * any Enrolment somebody is halfway through. A restored Operator can log in and cannot reach a
   * secret until they are reset and enrol again.
   *
   * <p>Blocks: it crosses the socket, and a password is stretched with Argon2id while it does. Must
   * not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  BackupOutcome exportBackupTo(Session session, Path destination, char[] password);

  /**
   * Replaces every Account and every setting in this deployment with the ones a Backup carries.
   *
   * <p><b>Wholesale, and never a merge</b> — ADR-0006 again, because Accounts from two origins in
   * one store produce states nobody can reason about. Whatever this machine held is gone when this
   * returns successfully, which is why the screen that calls it says so and asks a second time.
   *
   * <p>A refusal changes nothing: the file has to open, be read whole and be found to be this
   * build's before a row is written, and then it is written in one transaction. The Session ends
   * with the deployment it belonged to, so the caller's part afterwards is to say what was restored
   * and send the person back to the login screen.
   *
   * <p>Blocks: it crosses the socket, and a password is stretched with Argon2id while it does. Must
   * not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  RestoreOutcome importBackupFrom(Session session, Path source, char[] password);

  /**
   * Reports that the Operator did something, which is what starts the Session's countdown again.
   *
   * <p>This is the SessionGuard's whole job. It reports; it does not decide, and the answer that
   * comes back may well be that the Session was over before the report arrived.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  SessionStatus reportActivity(Session session);

  /**
   * Asks whether a Session is still live, and how long it has left.
   *
   * <p>Asking is not activity: this is how the guard finds out that the Session it is watching has
   * run out, and it must not be what keeps it alive.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  SessionStatus stillLive(Session session);

  /**
   * Ends a Session because the Operator asked to.
   *
   * <p>A Session that had already ended is not an error here: either way it is over, which is all
   * the caller wanted.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  void logOut(Session session);

  /**
   * Reports that the person holding this Session has read the notice saying their password was
   * reset, so that the service stops sending it.
   *
   * <p>Until this is called the notice arrives with every admission. That is deliberate: a notice
   * that was sent is not a notice that arrived, and a client that died before drawing a window would
   * otherwise have spent the only copy of the one thing this service says that has to reach a
   * particular person.
   *
   * <p>Reading a notice is not activity and this does not restart the Session's countdown.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  void passwordResetNoticeWasRead(Session session);

  /**
   * Asks the SecretVault for one named secret, on behalf of the Session that is holding it open.
   *
   * <p>This is the whole of story 55: a ProtectedFeature that needs a credential for a system it
   * connects to asks for it by name and is handed it. One secret at a time, decrypted by the service
   * at the moment of the request — there is nothing here that asks for the Vault as a whole, and
   * nothing anywhere that asks for the key, which never leaves the privileged process.
   *
   * <p>The Session is what opens it. An Operator's password derived the key that unwrapped the
   * DataKey when they logged in, so a client that skipped the login screen has nothing to ask with:
   * the Vault does not open because a check passed, and there is no check here to patch.
   *
   * <p>Asking for a secret is not activity and does not restart the Session's countdown. A product
   * that polls for a credential would otherwise keep alive the Session of somebody who has walked
   * away.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  SecretOutcome secretNamed(Session session, String name);

  /**
   * Puts a named secret into the SecretVault, replacing whatever was kept under that name.
   *
   * <p>The other half of a Vault a host product can actually use: something has to put the
   * credentials there, and it is the ProtectedFeature that knows what they are. Like reading one, it
   * is an Operator's request — the Administrator is refused by the service on both sides, so there is
   * no way in through the writing half either.
   *
   * <p>Blocks: it crosses the socket. Must not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  SecretKeepingOutcome keepSecret(Session session, String name, char[] secret);

  /**
   * Whether the AuthenticationService is there, may be reached by this account, and speaks this
   * build's protocol — asked once, before this application draws anything at all.
   *
   * <p>It is asked first because ADR-0002 makes the service the only party that can verify a
   * password: a login screen in front of a service that is not there is a gate that cannot gate
   * anything, and looking like one is worse than plainly refusing to be one. What comes back is
   * either reachable or one of three reasons, because the three have different remedies and telling
   * a person "something went wrong" hands them nothing they can act on.
   *
   * <p>Being told the service is reachable is not being told anything about this deployment — not
   * whether it has been set up, and not what it holds. It is answered to a peer who has proved
   * nothing, and so it says nothing.
   *
   * <p>Blocks, though within a bounded time: the attempt is given up on rather than waited out.
   * Must not be called on the JavaFX application thread — the whole point of the bound is that a
   * person is told what is wrong, and a frozen window tells them nothing.
   */
  ServiceReachability reachability();

  /**
   * Whether this installation is still waiting for its single Administrator, which is what decides
   * whether a person sees the first-run wizard or the login screen.
   *
   * <p>The answer says nothing about who the Administrator is or would be — only that there is not
   * one yet, which is what a fresh install shows the moment it opens a window anyway.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  boolean firstRunNeeded();

  /**
   * Offers a name and a password for the single Administrator.
   *
   * <p>Being told the first run is needed is not being allowed to run it: the service accepts this
   * only from a peer that administers the machine, and only while no Administrator exists. Both
   * refusals come back as a {@link FirstRunRefused} rather than being decided here, because a
   * client that decided them could be patched into deciding otherwise.
   *
   * <p>Blocks: the password is hashed on the other side, which is deliberately slow, so this must
   * not be called on the JavaFX application thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  FirstRunOutcome createAdministrator(String administratorName, char[] password);

  /**
   * Offers a one-time enrolment secret and the password its holder has chosen for themselves.
   *
   * <p>No Session goes with it, and none comes back. Whoever is at this screen has not authenticated
   * and cannot: the Account they are enrolling has no password until this returns. What stands in
   * for a Session is the secret, which the AuthenticationService issued, hashed and expires — and
   * every way of not holding it is one refusal, decided over there.
   *
   * <p>Blocks: the password is hashed at the other end. Must not be called on the JavaFX application
   * thread.
   *
   * @throws ServiceUnreachableException if the AuthenticationService could not be asked at all
   */
  EnrolmentOutcome completeEnrolment(String accountName, char[] secret, char[] password);

  /**
   * Opens whichever window this installation needs on {@code stage} — the first-run wizard while
   * there is no Administrator, the login screen once there is — and, once an Operator is admitted,
   * closes it and opens the view {@code protectedFeature} builds on a stage of its own.
   *
   * <p>Somebody who asked for the administration panel instead is handed the same way to a window
   * of the gate's own, and back to the login screen when they are done with it — the host product's
   * view is never built for them, because an Administrator does not reach it.
   *
   * <p>That stage is the gate's, not the host product's. It carries the view it was handed and one
   * control of the gate's own above it, which is where an Operator logs out; and it closes, handing
   * the person back to the login screen, when the AuthenticationService says the Session is over. A
   * host product writes none of that and cannot forget to.
   *
   * <p>This is the whole of the flow, and it is the same whichever gate is behind it, which is why
   * it is given rather than left to each implementation to get right.
   *
   * <p>Everything shown before somebody is admitted is drawn in the language of the machine this
   * runs on, with a selector on the login screen for when that is not the language of whoever is at
   * the keyboard; everything opened by an admission is drawn in the LanguagePreference of the
   * Account admitted. A host product chooses none of that and is asked for none of it.
   *
   * <p>Nothing is drawn where the AuthenticationService cannot be reached. That is story 90 and it
   * is not a detail of this method: ADR-0002 makes the service the only party that can verify a
   * password, so a login screen in front of one that is not there is a gate that cannot gate
   * anything. What appears instead names which of "not running", "incompatible version" and "socket
   * not accessible" happened, and closes.
   *
   * <p>Must be called on the JavaFX application thread, and returns before any window is on the
   * stage. The two questions that decide which window it is are asked off that thread — the first
   * of them waits on a socket for as long as {@link
   * com.javafxlogin.core.ipc.ServiceHandshake#PATIENCE} on a machine where nothing answers — and a
   * window drawn first would be a window frozen in front of somebody for exactly that long.
   */
  default void protect(Stage stage, Function<Session, Parent> protectedFeature) {
    GateFlow.open(this, stage, protectedFeature, InterfaceLanguage.ofTheMachine());
  }
}
