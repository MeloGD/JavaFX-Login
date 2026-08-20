package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.Session;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;

/**
 * What the administration panel does: list the Accounts of the deployment, ask the service to
 * change one of them, configure how long a Session may idle, and copy the record out.
 *
 * <p>It decides nothing. Every one of those is a request the AuthenticationService answers or
 * refuses, and this class shows which of the two happened — including the refusals that exist
 * because of what an Administrator is: the single Administrator cannot be deleted and cannot be
 * handed an enrolment secret, and the panel says so rather than hiding the controls, because a
 * control that is not there teaches nobody why.
 *
 * <p>Nothing here chooses anybody's password, and there is nowhere on this screen to type one. That
 * is ASVS 5.0 §6.4.6 and story 21: what an Administrator gets instead is a one-time secret to hand
 * over, shown once and never readable again.
 */
public final class AdministrationController {

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String SECRET_WARNING =
      "Anótalo ahora y entrégaselo en persona: no se volverá a mostrar y no hay forma de"
          + " recuperarlo. Caduca el %s.";

  private static final String DELETE_CONSEQUENCES =
      "Se eliminará la cuenta «%s» y con ella su copia de la clave del almacén de secretos:"
          + " esa persona dejará de poder entrar y de poder leer secretos. Los secretos guardados"
          + " siguen ahí para el resto de operadores. No se puede deshacer.";

  private static final String ACCOUNT_CREATED = "Cuenta «%s» creada.";
  private static final String PASSWORD_RESET =
      "La contraseña de «%s» ha dejado de funcionar ahora mismo.";

  /**
   * An Account awaiting enrolment had no password to take away, and the service records no reset
   * for it. Saying one happened would be the panel asserting an event the service did not make.
   */
  private static final String ENROLMENT_SECRET_REISSUED =
      "Se ha vuelto a emitir el código de «%s», que seguía sin contraseña.";
  private static final String ACCOUNT_DELETED = "Cuenta «%s» eliminada.";
  private static final String LOCKOUT_CLEARED = "Se ha desbloqueado «%s».";
  private static final String PERIOD_CHANGED =
      "Las sesiones caducan tras %d minutos de inactividad, a partir de ahora.";
  private static final String EXPIRY_DISABLED =
      "Las sesiones ya no caducan por inactividad en este equipo.";
  private static final String NOT_A_PERIOD =
      "Escribe los minutos de inactividad como un número entero mayor que cero.";
  private static final String NAME_NEEDED = "Escribe el nombre de la cuenta que quieres crear.";
  private static final String NOTHING_SELECTED = "Elige antes una cuenta de la lista.";
  private static final String DESTINATION_NEEDED =
      "Escribe la ruta completa del archivo donde quieres la copia del registro.";
  private static final String EXPORTED = "Se han copiado %d eventos a %s.";
  private static final String EXPORTED_WITH_A_BROKEN_CHAIN =
      "Se han copiado %d eventos a %s, pero la cadena de integridad no cuadra: el registro se ha"
          + " editado o le faltan entradas.";

  /** The moment as this machine writes moments for a person, not as the record writes them. */
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

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

  @FXML private TextField inactivityMinutes;
  @FXML private CheckBox neverExpires;
  @FXML private TextField exportDestination;
  @FXML private CheckBox secondFactor;

  @FXML private Label message;

  private LoginGate gate;
  private Session session;
  private Consumer<String> handBack;
  private SessionGuard guard;
  private boolean over;

  /** Wires the columns to what each one says about an Account, before anything is listed. */
  @FXML
  private void initialize() {
    accountName.setCellValueFactory(row -> text(row.getValue().name()));
    role.setCellValueFactory(row -> text(AccountText.nameOf(row.getValue().role())));
    passwordStrength.setCellValueFactory(
        row -> text(AccountText.bandOf(row.getValue().passwordStrength())));
    languagePreference.setCellValueFactory(
        row -> text(AccountText.preferenceOf(row.getValue().languagePreference())));
    lockout.setCellValueFactory(row -> text(AccountText.lockoutOf(row.getValue().lockedFor())));
    // Nothing is proposed about somebody else's Account until somebody is chosen.
    accounts
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((item, was, is) -> forgetTheDelete());
    // A number of minutes means nothing while expiry is switched off, and a box that still took one
    // would be a screen saying two things at once.
    inactivityMinutes.disableProperty().bind(neverExpires.selectedProperty());
    // The seam issue #12 asks for: visible, disabled, and doing nothing at all. It is disabled in
    // the FXML as well; both, because this is the control whose being inert is the feature.
    secondFactor.setSelected(false);
    secondFactor.setDisable(true);
  }

  private static ReadOnlyStringWrapper text(String value) {
    return new ReadOnlyStringWrapper(value);
  }

  /**
   * Watches the Session this panel runs under and asks for the Accounts, which is what the screen
   * is drawn from.
   */
  void administer(LoginGate gate, Session session, Consumer<String> handBack) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.session = Objects.requireNonNull(session, "session");
    this.handBack = Objects.requireNonNull(handBack, "handBack");

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
      say(NAME_NEEDED);
      return;
    }
    ask(
        "administration-create",
        () -> gate.createOperator(session, name),
        provisioned -> {
          if (provisioned instanceof EnrolmentSecretIssued) {
            newOperatorName.clear();
            say(ACCOUNT_CREATED.formatted(name));
          }
          showTheSecretOf(provisioned);
        });
  }

  @FXML
  private void onResetPassword() {
    withTheChosenAccount(
        chosen -> {
          String said =
              (chosen.isAwaitingEnrolment() ? ENROLMENT_SECRET_REISSUED : PASSWORD_RESET)
                  .formatted(chosen.name());
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
        enrolmentSecretWarning.setText(SECRET_WARNING.formatted(WHEN.format(issued.expiresAt())));
        showTheSecret(true);
        listTheAccounts();
      }
      case PolicyRefusal refusal -> say(PolicyViolationText.paragraphFor(refusal.violations()));
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
          deleteConsequences.setText(DELETE_CONSEQUENCES.formatted(chosen.name()));
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
              outcome -> administered(outcome, ACCOUNT_DELETED.formatted(chosen.name())));
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
                outcome -> administered(outcome, LOCKOUT_CLEARED.formatted(chosen.name()))));
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
      said = EXPIRY_DISABLED;
    } else {
      long minutes = minutesTyped();
      if (minutes <= 0) {
        say(NOT_A_PERIOD);
        return;
      }
      period = InactivityPeriod.of(Duration.ofMinutes(minutes));
      said = PERIOD_CHANGED.formatted(minutes);
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
      say(DESTINATION_NEEDED);
      return;
    }
    Path destination;
    try {
      destination = Path.of(typed);
    } catch (InvalidPathException e) {
      // The same sentence the service answers with, because it is the same thing: a destination
      // this export is not going to be written to, and another path is what fixes it.
      say(
          AdministrationRefusedText.sentenceFor(
              AdministrationRefusedReason.EXPORT_DESTINATION_REFUSED));
      return;
    }
    ask(
        "administration-export",
        () -> gate.exportAuthenticationEventsTo(session, destination),
        outcome -> exported(outcome, destination));
  }

  private void exported(ExportOutcome outcome, Path destination) {
    switch (outcome) {
      case EventsExported copied ->
          say(
              (copied.export().chainIntact() ? EXPORTED : EXPORTED_WITH_A_BROKEN_CHAIN)
                  .formatted(copied.export().events(), destination));
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
   * all: a Session that has ended is the end of this window, whichever click discovered it.
   */
  private void refused(AdministrationRefused refused) {
    String sentence = AdministrationRefusedText.sentenceFor(refused.reason());
    if (refused.reason() == AdministrationRefusedReason.SESSION_OVER) {
      theSessionEnded(sentence);
      return;
    }
    say(sentence);
  }

  /**
   * Does something to the Account somebody chose from the list, or says that nobody has been
   * chosen. What is handed over is the Account rather than its name, because two of the three
   * things done to one are worded differently depending on what state it is in.
   */
  private void withTheChosenAccount(Consumer<AccountSummary> then) {
    AccountSummary chosen = accounts.getSelectionModel().getSelectedItem();
    if (chosen == null) {
      say(NOTHING_SELECTED);
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
    GateAttempt.make(threadName, question, answered, this::say);
  }

  private void say(String sentence) {
    message.setText(sentence);
  }

  /**
   * However the Session ended — a logout, the clocks, a service that went away — this window is
   * done, and the person is handed back to the login screen.
   */
  private void theSessionEnded(String sentence) {
    if (over) {
      return;
    }
    over = true;
    stopWatching();
    handBack.accept(sentence);
  }
}
