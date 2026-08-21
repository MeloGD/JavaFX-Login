package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.Session;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.util.StringConverter;

/**
 * What the administration panel does: list the Accounts of the deployment, ask the service to
 * change one of them, configure how long a Session may idle, copy the record out, and back the
 * deployment up or restore it.
 *
 * <p>It decides nothing. Every one of those is a request the AuthenticationService answers or
 * refuses, and this class shows which of the two happened — including the refusals that exist
 * because of what an Administrator is: the single Administrator cannot be deleted and cannot be
 * handed an enrolment secret, and the panel says so rather than hiding the controls, because a
 * control that is not there teaches nobody why.
 *
 * <p>Nothing here chooses anybody's password, and there is nowhere on this screen to type an
 * Account's. That is ASVS 5.0 §6.4.6 and story 21: what an Administrator gets instead is a one-time
 * secret to hand over, shown once and never readable again.
 *
 * <p>There is one password box, and it is not an exception to that. What is typed in it is what a
 * Backup file is encrypted under — it belongs to a file rather than to a person, nothing verifies it
 * against anything, and knowing it admits nobody. ADR-0006 is why it exists at all: a backup bound
 * to the machine that wrote it would be useless on the day that machine dies, so what is left
 * protecting it is a password and Argon2id.
 *
 * <p>It is drawn in the language the Administrator's own Account reads, and every sentence on it
 * comes out of that language's bundle. The one thing on it that is <em>about</em> a language rather
 * than said in one is issue #13's control: which language somebody else's screens are drawn in,
 * which is a fact about their Account and takes effect the next time they are admitted.
 */
public final class AdministrationController {

  private static final String SECRET_WARNING = "administration.secret-warning";

  private static final String DELETE_CONSEQUENCES = "administration.delete-consequences";

  private static final String ACCOUNT_CREATED = "administration.account-created";
  private static final String PASSWORD_RESET = "administration.password-reset";

  /**
   * An Account awaiting enrolment had no password to take away, and the service records no reset
   * for it. Saying one happened would be the panel asserting an event the service did not make.
   */
  private static final String ENROLMENT_SECRET_REISSUED =
      "administration.enrolment-secret-reissued";

  private static final String ACCOUNT_DELETED = "administration.account-deleted";
  private static final String LOCKOUT_CLEARED = "administration.lockout-cleared";
  private static final String LANGUAGE_CHANGED = "administration.language-changed";
  private static final String LANGUAGE_FOLLOWS_THE_MACHINE =
      "administration.language-follows-the-machine";
  private static final String MACHINE_LANGUAGE = "administration.language.machine";
  private static final String PERIOD_CHANGED = "administration.period-changed";
  private static final String EXPIRY_DISABLED = "administration.expiry-disabled";
  private static final String NOT_A_PERIOD = "administration.not-a-period";
  private static final String NAME_NEEDED = "administration.name-needed";
  private static final String NOTHING_SELECTED = "administration.nothing-selected";
  private static final String DESTINATION_NEEDED = "administration.destination-needed";
  private static final String EXPORTED = "administration.exported";
  private static final String EXPORTED_WITH_A_BROKEN_CHAIN =
      "administration.exported-with-a-broken-chain";

  private static final String BACKUP_FILE_NEEDED = "administration.backup-file-needed";
  private static final String BACKUP_PASSWORD_NEEDED = "administration.backup-password-needed";
  private static final String BACKUP_EXPORTED = "administration.backup-exported";
  private static final String IMPORT_CONSEQUENCES = "administration.import-consequences";

  /**
   * What the login screen says after an import, rather than what this panel says: the import is the
   * end of this window, because the Session it ran under named an Account in a deployment that has
   * just been replaced.
   */
  private static final String BACKUP_IMPORTED = "administration.backup-imported";

  /**
   * What an Account that has said nothing about its language is chosen from the selector as. It is
   * a Locale naming no language, which is what "follow whichever machine you are read on" is: a tag
   * would freeze that to the machine it was chosen on.
   */
  private static final Locale THE_MACHINE_S = Locale.ROOT;

  @FXML private BorderPane root;
  @FXML private TableView<AccountSummary> accounts;
  @FXML private TableColumn<AccountSummary, String> accountName;
  @FXML private TableColumn<AccountSummary, String> role;
  @FXML private TableColumn<AccountSummary, String> passwordStrength;
  @FXML private TableColumn<AccountSummary, String> languagePreference;
  @FXML private TableColumn<AccountSummary, String> lockout;

  @FXML private TextField newOperatorName;
  @FXML private Label enrolmentSecret;
  @FXML private Label enrolmentSecretWarning;
  @FXML private Button enrolmentSecretRead;

  @FXML private Label deleteConsequences;
  @FXML private Button confirmDelete;
  @FXML private Button cancelDelete;

  @FXML private ComboBox<Locale> languageChoice;

  @FXML private TextField backupFile;
  @FXML private PasswordField backupPassword;
  @FXML private Label importConsequences;
  @FXML private Button confirmImport;
  @FXML private Button cancelImport;

  @FXML private TextField inactivityMinutes;
  @FXML private CheckBox neverExpires;
  @FXML private TextField exportDestination;
  @FXML private CheckBox secondFactor;

  @FXML private Label message;

  private LoginGate gate;
  private InterfaceLanguage saidIn;
  private Session session;
  private Consumer<String> handBack;
  private SessionGuard guard;
  private boolean over;

  /** Wires everything that does not depend on which language this panel is being drawn in. */
  @FXML
  private void initialize() {
    // Nothing is proposed about somebody else's Account until somebody is chosen.
    accounts
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (item, was, is) -> {
              forgetTheDelete();
              showTheLanguageOf(is);
            });
    // A number of minutes means nothing while expiry is switched off, and a box that still took one
    // would be a screen saying two things at once.
    inactivityMinutes.disableProperty().bind(neverExpires.selectedProperty());
    // The seam issue #12 asks for: visible, disabled, and doing nothing at all. It is disabled in
    // the FXML as well; both, because this is the control whose being inert is the feature.
    secondFactor.setSelected(false);
    secondFactor.setDisable(true);
  }

  /**
   * Wires the columns and the language selector to what each one says, in the language this panel
   * is drawn in.
   *
   * <p>Apart from {@link #initialize()} because it needs that language, and the FXML has been
   * loaded before anybody has handed one over — the wording of a Role is not a fact about the
   * screen, it is a fact about who is reading it.
   */
  private void wireWhatIsWorded() {
    accountName.setCellValueFactory(row -> text(row.getValue().name()));
    role.setCellValueFactory(row -> text(AccountText.nameOf(saidIn, row.getValue().role())));
    passwordStrength.setCellValueFactory(
        row -> text(AccountText.bandOf(saidIn, row.getValue().passwordStrength())));
    languagePreference.setCellValueFactory(
        row -> text(AccountText.preferenceOf(saidIn, row.getValue().languagePreference())));
    lockout.setCellValueFactory(
        row -> text(AccountText.lockoutOf(saidIn, row.getValue().lockedFor())));
    offerTheLanguages();
  }

  /**
   * The languages an Account can be set to read: the ones this build ships, and the machine's own.
   *
   * <p>Each is named in itself, as the login screen's selector names them, because an Administrator
   * choosing a language for somebody else is choosing something that person has to recognise.
   */
  private void offerTheLanguages() {
    languageChoice.getItems().setAll(THE_MACHINE_S);
    languageChoice.getItems().addAll(InterfaceLanguage.offered());
    languageChoice.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(Locale offered) {
            if (offered == null || offered.getLanguage().isEmpty()) {
              return saidIn.say(MACHINE_LANGUAGE);
            }
            return InterfaceLanguage.nameOf(offered);
          }

          @Override
          public Locale fromString(String named) {
            throw new UnsupportedOperationException("the selector is not typed into");
          }
        });
    languageChoice.setValue(THE_MACHINE_S);
  }

  private static ReadOnlyStringWrapper text(String value) {
    return new ReadOnlyStringWrapper(value);
  }

  /**
   * Watches the Session this panel runs under and asks for the Accounts, which is what the screen
   * is drawn from.
   *
   * @param saidIn the language the Administrator's own Account reads, or whatever was being read at
   *     the login screen where it has said nothing
   */
  void administer(
      LoginGate gate, Session session, InterfaceLanguage saidIn, Consumer<String> handBack) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.session = Objects.requireNonNull(session, "session");
    this.saidIn = Objects.requireNonNull(saidIn, "saidIn");
    this.handBack = Objects.requireNonNull(handBack, "handBack");

    wireWhatIsWorded();
    guard = SessionGuard.watching(root, gate, session, this::theSessionEnded);
    listTheAccounts();
  }

  /** Stops the guard, whether or not this window was the one that noticed the Session end. */
  void stopWatching() {
    if (guard != null) {
      guard.stop();
    }
  }

  private void listTheAccounts() {
    ask("administration-accounts", () -> gate.accounts(session), this::show);
  }

  private void show(AccountListing listing) {
    switch (listing) {
      case AccountsSeen seen -> replaceTheList(seen.accounts());
      case AdministrationRefused refused -> refused(refused);
    }
  }

  /**
   * Puts the service's answer on the screen, keeping whoever was chosen chosen where they are still
   * there — an Administrator who clears a Lockout should not have to find the row again.
   */
  private void replaceTheList(List<AccountSummary> listed) {
    String chosen = chosenName();
    accounts.setItems(FXCollections.observableArrayList(listed));
    listed.stream()
        .filter(account -> account.name().equals(chosen))
        .findFirst()
        .ifPresent(account -> accounts.getSelectionModel().select(account));
  }

  @FXML
  private void onCreateOperator() {
    String name = newOperatorName.getText().trim();
    if (name.isEmpty()) {
      say(saidIn.say(NAME_NEEDED));
      return;
    }
    ask(
        "administration-create",
        () -> gate.createOperator(session, name),
        provisioned -> {
          if (provisioned instanceof EnrolmentSecretIssued) {
            newOperatorName.clear();
            say(saidIn.say(ACCOUNT_CREATED, name));
          }
          showTheSecretOf(provisioned);
        });
  }

  @FXML
  private void onResetPassword() {
    withTheChosenAccount(
        chosen -> {
          String said =
              saidIn.say(
                  chosen.isAwaitingEnrolment() ? ENROLMENT_SECRET_REISSUED : PASSWORD_RESET,
                  chosen.name());
          ask(
              "administration-reset",
              () -> gate.resetThePasswordOf(session, chosen.name()),
              provisioned -> {
                if (provisioned instanceof EnrolmentSecretIssued) {
                  say(said);
                }
                showTheSecretOf(provisioned);
              });
        });
  }

  /**
   * Shows the one thing the service will never say again, or says why there is nothing to show.
   *
   * <p>The secret goes on the screen with the warning beside it and no way to ask for it back:
   * there is no request on the {@link LoginGate} that reads one, because the CredentialStore keeps
   * a hash of it and nothing else.
   */
  private void showTheSecretOf(AccountProvisioned provisioned) {
    switch (provisioned) {
      case EnrolmentSecretIssued issued -> {
        enrolmentSecret.setText(issued.secret());
        enrolmentSecretWarning.setText(
            saidIn.say(SECRET_WARNING, saidIn.moments().format(issued.expiresAt())));
        showTheSecret(true);
        listTheAccounts();
      }
      case PolicyRefusal refusal ->
          say(PolicyViolationText.paragraphFor(saidIn, refusal.violations()));
      case AdministrationRefused refused -> refused(refused);
    }
  }

  /** The Administrator says they have written it down, which is the only thing that ends it. */
  @FXML
  private void onEnrolmentSecretRead() {
    enrolmentSecret.setText("");
    enrolmentSecretWarning.setText("");
    showTheSecret(false);
  }

  private void showTheSecret(boolean shown) {
    show(shown, enrolmentSecret, enrolmentSecretWarning, enrolmentSecretRead);
  }

  /**
   * Story 62's first half: what deleting an Operator costs is on the screen before anything is
   * deleted, and the delete itself is a second, separate click.
   */
  @FXML
  private void onDeleteOperator() {
    withTheChosenAccount(
        chosen -> {
          deleteConsequences.setText(saidIn.say(DELETE_CONSEQUENCES, chosen.name()));
          showTheDelete(true);
        });
  }

  @FXML
  private void onConfirmDelete() {
    withTheChosenAccount(
        chosen -> {
          forgetTheDelete();
          ask(
              "administration-delete",
              () -> gate.deleteOperator(session, chosen.name()),
              outcome -> administered(outcome, saidIn.say(ACCOUNT_DELETED, chosen.name())));
        });
  }

  @FXML
  private void onCancelDelete() {
    forgetTheDelete();
  }

  private void forgetTheDelete() {
    deleteConsequences.setText("");
    showTheDelete(false);
  }

  private void showTheDelete(boolean asked) {
    show(asked, deleteConsequences, confirmDelete, cancelDelete);
  }

  /**
   * Shows or hides a group of controls together. Unmanaged while hidden rather than merely
   * invisible, so that a warning nobody is being shown takes no room on the screen.
   */
  private static void show(boolean shown, Node... controls) {
    for (Node control : controls) {
      control.setVisible(shown);
      control.setManaged(shown);
    }
  }

  @FXML
  private void onClearLockout() {
    withTheChosenAccount(
        chosen ->
            ask(
                "administration-clear-lockout",
                () -> gate.clearTheLockoutOf(session, chosen.name()),
                outcome -> administered(outcome, saidIn.say(LOCKOUT_CLEARED, chosen.name()))));
  }

  /**
   * Puts the selector on whatever the chosen Account holds, so that what it shows is that Account's
   * language rather than the last one somebody looked at.
   */
  private void showTheLanguageOf(AccountSummary chosen) {
    if (chosen == null) {
      languageChoice.setValue(THE_MACHINE_S);
      return;
    }
    languageChoice.setValue(chosen.languagePreference().orElse(THE_MACHINE_S));
  }

  /**
   * Issue #13's last criterion: which language the person using an Account reads the interface in.
   *
   * <p>It changes nothing about this panel, and that is not an omission. What is recorded is a fact
   * about somebody else's Account, and it reaches their screens the way every other fact about an
   * Account does — at the admission that proves the Account is theirs. An Administrator setting
   * their own is in the same position, one logout later.
   */
  @FXML
  private void onApplyLanguage() {
    withTheChosenAccount(
        chosen -> {
          Optional<Locale> preference = theLanguageChosen();
          String said =
              preference
                  .map(
                      language ->
                          saidIn.say(
                              LANGUAGE_CHANGED,
                              chosen.name(),
                              InterfaceLanguage.nameOf(language)))
                  .orElseGet(() -> saidIn.say(LANGUAGE_FOLLOWS_THE_MACHINE, chosen.name()));
          ask(
              "administration-language",
              () -> gate.useLanguagePreference(session, chosen.name(), preference),
              outcome -> administered(outcome, said));
        });
  }

  /** A language, or the machine's — which is the absence of one rather than a language itself. */
  private Optional<Locale> theLanguageChosen() {
    Locale chosen = languageChoice.getValue();
    if (chosen == null || chosen.getLanguage().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(chosen);
  }

  /**
   * Changes how long a Session may idle here, or switches expiry off, which is what a kiosk
   * deployment is.
   *
   * <p>What is typed is read as whole minutes and nothing else. The refusal below is not a policy —
   * the service has its own and applies it whatever this client sends — it is this window declining
   * to guess what somebody meant by a box with a word in it.
   */
  @FXML
  private void onApplyInactivityPeriod() {
    InactivityPeriod period;
    String said;
    if (neverExpires.isSelected()) {
      period = InactivityPeriod.disabled();
      said = saidIn.say(EXPIRY_DISABLED);
    } else {
      long minutes = minutesTyped();
      if (minutes <= 0) {
        say(saidIn.say(NOT_A_PERIOD));
        return;
      }
      period = InactivityPeriod.of(Duration.ofMinutes(minutes));
      said = saidIn.say(PERIOD_CHANGED, minutes);
    }
    ask(
        "administration-inactivity-period",
        () -> gate.useInactivityPeriod(session, period),
        outcome -> administered(outcome, said));
  }

  /** Whole minutes, or nothing at all — including a number too large to be a period. */
  private long minutesTyped() {
    try {
      return Long.parseLong(inactivityMinutes.getText().trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Story 75: the record leaves as a file the Administrator names, and never as a screen. */
  @FXML
  private void onExport() {
    String typed = exportDestination.getText().trim();
    if (typed.isEmpty()) {
      say(saidIn.say(DESTINATION_NEEDED));
      return;
    }
    Path destination;
    try {
      destination = Path.of(typed);
    } catch (InvalidPathException e) {
      // The same sentence the service answers with, because it is the same thing: a destination
      // this export is not going to be written to, and another path is what fixes it.
      say(
          saidIn.say(
              AdministrationRefusedText.keyFor(
                  AdministrationRefusedReason.EXPORT_DESTINATION_REFUSED)));
      return;
    }
    ask(
        "administration-export",
        () -> gate.exportAuthenticationEventsTo(session, destination),
        outcome -> exported(outcome, destination));
  }

  /**
   * Writes a Backup that restores on another machine, sealed under a password typed here.
   *
   * <p>The password box on this screen is the one thing that looks like the thing this panel does
   * not have. It is not an Account's password and nothing verifies it against anything: it is what
   * the file is encrypted under, and ADR-0006 makes it the whole of what protects a file that is
   * meant to restore anywhere. Nobody is enrolled by typing it and nobody is admitted by knowing it.
   */
  @FXML
  private void onExportBackup() {
    withTheBackupAsked(
        AdministrationRefusedReason.BACKUP_DESTINATION_REFUSED,
        (file, password) ->
            ask(
                "administration-backup-export",
                password,
                () -> gate.exportBackupTo(session, file, password),
                outcome -> backedUp(outcome, file)));
  }

  /**
   * Issue #14's sixth criterion: what an import costs is on the screen before anything is destroyed,
   * and the import itself is a second, separate click — the same shape the delete has, because it is
   * the same kind of thing done to every Account at once.
   */
  @FXML
  private void onImportBackup() {
    // The file only. This click asks nobody for anything and destroys nothing — it puts a sentence
    // on the screen — so the password is left in the box it was typed into and read at the second
    // click, which is the one that has something to do with it.
    theBackupFile(AdministrationRefusedReason.BACKUP_SOURCE_REFUSED)
        .ifPresent(
            file -> {
              importConsequences.setText(saidIn.say(IMPORT_CONSEQUENCES, file));
              showTheImport(true);
            });
  }

  @FXML
  private void onConfirmImport() {
    withTheBackupAsked(
        AdministrationRefusedReason.BACKUP_SOURCE_REFUSED,
        (file, password) -> {
          forgetTheImport();
          ask(
              "administration-backup-import",
              password,
              () -> gate.importBackupFrom(session, file, password),
              this::restored);
        });
  }

  @FXML
  private void onCancelImport() {
    forgetTheImport();
  }

  private void forgetTheImport() {
    importConsequences.setText("");
    showTheImport(false);
  }

  private void showTheImport(boolean asked) {
    show(asked, importConsequences, confirmImport, cancelImport);
  }

  /**
   * Both halves a Backup needs: the file, and the password it is sealed under.
   *
   * @param whenItIsNoPath which refusal to say where what was typed is not a path on this machine,
   *     because the two directions are refused in different words — see {@link #theBackupFile}
   */
  private void withTheBackupAsked(
      AdministrationRefusedReason whenItIsNoPath, BiConsumer<Path, char[]> then) {
    theBackupFile(whenItIsNoPath)
        .ifPresent(file -> then.accept(file, backupPassword.getText().toCharArray()));
  }

  /**
   * The file a Backup is to be written to or read from, or nothing at all where the screen does not
   * yet say which — and then it says which half is missing.
   *
   * <p>The password is asked for here too, because a Backup with no password to seal it is not a
   * Backup, and finding that out after the file had been named would be a worse moment to find it
   * out in. What is handed back is the file alone: one of the two callers has no business with the
   * password yet.
   *
   * <p>A path that is not one on this machine is said in the words the service refuses one with, for
   * the reason the export of the record says it that way: it is the same thing — a file this is not
   * going to happen to, and another path is what fixes it. Which of the two refusals is the caller's
   * to say, because the service keeps them apart on purpose: an Administrator who has just been told
   * a path was refused should not have to work out which of the two things they asked for it was
   * about.
   */
  private Optional<Path> theBackupFile(AdministrationRefusedReason whenItIsNoPath) {
    String typed = backupFile.getText().trim();
    if (typed.isEmpty()) {
      say(saidIn.say(BACKUP_FILE_NEEDED));
      return Optional.empty();
    }
    if (backupPassword.getText().isEmpty()) {
      say(saidIn.say(BACKUP_PASSWORD_NEEDED));
      return Optional.empty();
    }
    try {
      return Optional.of(Path.of(typed));
    } catch (InvalidPathException e) {
      say(saidIn.say(AdministrationRefusedText.keyFor(whenItIsNoPath)));
      return Optional.empty();
    }
  }

  private void backedUp(BackupOutcome outcome, Path destination) {
    switch (outcome) {
      case BackupWritten written -> {
        backupPassword.clear();
        say(
            saidIn.say(
                BACKUP_EXPORTED,
                written.backup().accounts(),
                written.backup().settings(),
                destination));
      }
      case AdministrationRefused refused -> refused(refused);
    }
  }

  /**
   * What happens after a Backup has replaced the deployment: this window is over.
   *
   * <p>Not because the request failed, but because it worked. The Session this panel ran under was
   * granted to an Account in a store that no longer exists, and the login screen it hands back to
   * belongs to the deployment that was just restored — where the password to get back in is whatever
   * it was on the machine the Backup came from.
   */
  private void restored(RestoreOutcome outcome) {
    switch (outcome) {
      case BackupRestored ignored -> {
        backupPassword.clear();
        theSessionEnded(BACKUP_IMPORTED);
      }
      case AdministrationRefused refused -> refused(refused);
    }
  }

  private void exported(ExportOutcome outcome, Path destination) {
    switch (outcome) {
      case EventsExported copied ->
          say(
              saidIn.say(
                  copied.export().chainIntact() ? EXPORTED : EXPORTED_WITH_A_BROKEN_CHAIN,
                  copied.export().events(),
                  destination));
      case AdministrationRefused refused -> refused(refused);
    }
  }

  /** Story 49, for the Role that does not reach the ProtectedFeature: a way to leave deliberately. */
  @FXML
  private void onLogOut() {
    ask(
        "administration-logout",
        () -> {
          gate.logOut(session);
          return SessionEndedText.LOGGED_OUT;
        },
        this::theSessionEnded);
  }

  private void administered(AdministrationOutcome outcome, String said) {
    switch (outcome) {
      case Administered ignored -> {
        say(said);
        listTheAccounts();
      }
      case AdministrationRefused refused -> refused(refused);
    }
  }

  /**
   * A refusal is said and nothing else happens — except the one that is not about this request at
   * all: a Session that has ended is the end of this window, whichever click discovered it. That
   * one is handed on as the key it is, because the window that says it is the login screen.
   */
  private void refused(AdministrationRefused refused) {
    String key = AdministrationRefusedText.keyFor(refused.reason());
    if (refused.reason() == AdministrationRefusedReason.SESSION_OVER) {
      theSessionEnded(key);
      return;
    }
    say(saidIn.say(key));
  }

  /**
   * Does something to the Account somebody chose from the list, or says that nobody has been
   * chosen. What is handed over is the Account rather than its name, because three of the four
   * things done to one are worded differently depending on what state it is in.
   */
  private void withTheChosenAccount(Consumer<AccountSummary> then) {
    AccountSummary chosen = accounts.getSelectionModel().getSelectedItem();
    if (chosen == null) {
      say(saidIn.say(NOTHING_SELECTED));
      return;
    }
    then.accept(chosen);
  }

  private String chosenName() {
    AccountSummary chosen = accounts.getSelectionModel().getSelectedItem();
    return chosen == null ? null : chosen.name();
  }

  /**
   * Every question this panel puts to the service is asked off the JavaFX application thread, as
   * every other window's is: they cross a socket, and one of them copies the whole record while the
   * window waits.
   */
  private <A> void ask(String threadName, Supplier<A> question, Consumer<A> answered) {
    GateAttempt.make(threadName, question, answered, key -> say(saidIn.say(key)));
  }

  /**
   * The same, for the two questions on this panel that carry a password: the file a Backup is
   * written to, and the file one is restored from.
   *
   * <p>The array is blanked once the attempt is over, however it ended, exactly as the login and
   * enrolment screens blank theirs. What that is and is not worth is worth being plain about: the
   * PasswordField it came out of holds the same characters as a String nothing here can overwrite,
   * so this shortens the life of one copy rather than of the secret. It is the copy that goes to
   * another thread and sits in a lambda's capture, which is the one worth shortening.
   */
  private <A> void ask(
      String threadName, char[] password, Supplier<A> question, Consumer<A> answered) {
    GateAttempt.make(threadName, password, question, answered, key -> say(saidIn.say(key)));
  }

  private void say(String sentence) {
    message.setText(sentence);
  }

  /**
   * However the Session ended — a logout, the clocks, a service that went away — this window is
   * done, and the person is handed back to the login screen, which says why in its own language.
   */
  private void theSessionEnded(String saying) {
    if (over) {
      return;
    }
    over = true;
    stopWatching();
    handBack.accept(saying);
  }
}
