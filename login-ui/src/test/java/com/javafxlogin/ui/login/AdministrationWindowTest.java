package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventExport;
import com.javafxlogin.core.backup.Backup;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.Session;
import com.javafxlogin.core.session.SessionEndedReason;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: the administration panel, driven by TestFX on Monocle with no display, against a fake
 * LoginGate.
 *
 * <p>Issue #12. What is asserted here is the window's behaviour — what it lists, what it asks the
 * service for, and what it says afterwards. Who is <em>allowed</em> to do any of it is the
 * AuthenticationService's decision and is asserted where that decision is made: nothing here could
 * prove an Administrator-only rule, because the gate this drives is a fake that would answer
 * anybody.
 */
class AdministrationWindowTest extends ApplicationTest {

  /**
   * The language every window in this test is drawn in, named rather than taken from the machine
   * the suite happens to be running on: what a screen says is asserted against the bundle it came
   * from, and a developer's locale is not a thing to assert against.
   */
  private static final InterfaceLanguage SPANISH =
      InterfaceLanguage.of(Locale.forLanguageTag("es"));

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";

  /** A language this build ships wording for, and not the one this panel is being drawn in. */
  private static final Locale ENGLISH = Locale.forLanguageTag("en");

  private static final int PATIENCE_IN_SECONDS = 10;

  private FakeLoginGate gate;
  private Stage loginStage;

  /** How many times the gate asked the host product to build its view. */
  private final AtomicInteger viewsBuilt = new AtomicInteger();

  @Override
  public void start(Stage stage) {
    loginStage = stage;
    gate =
        new FakeLoginGate()
            .administeredBy(ADMINISTRATOR, ADMINISTRATOR_PASSWORD)
            .holdingTheOperator(OPERATOR);
    GateFlow.open(gate, stage, session -> theHostsView(), SPANISH);
  }

  /** The host product's view, which an Administrator must never be handed. */
  private Parent theHostsView() {
    viewsBuilt.incrementAndGet();
    Label label = new Label("la funcionalidad protegida");
    label.setId("feature");
    return new StackPane(label);
  }

  /**
   * ADR-0005 and story 38: the Administrator manages Accounts and configuration, and the
   * ProtectedFeature is not theirs to reach. The view is not merely hidden from them — it is never
   * built, because the function that builds it is not called on this path at all.
   */
  @Test
  void theHostProductsViewIsNeverBuiltForAnAdministrator() {
    openThePanel();

    assertEquals(0, viewsBuilt.get(), "the host was asked to build its view for an Administrator");
    assertTrue(lookup("#feature").tryQuery().isEmpty(), "and it is not on the screen either");
    assertTrue(lookup("#accounts").tryQuery().isPresent(), "the panel should be");
  }

  /** Criterion 1: the Role, the coarse band, the language preference and the Lockout. */
  @Test
  void everyAccountIsListedWithWhatTheAdministratorNeedsToKnowAboutIt() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            Optional.of(PasswordStrength.WEAK),
            Optional.of(Locale.forLanguageTag("es-ES")),
            Optional.of(Duration.ofMinutes(10))));
    openThePanel();

    List<String> rows = listedAccounts();

    assertTrue(rows.stream().anyMatch(row -> row.contains(OPERATOR)), () -> "no Operator in " + rows);
    assertTrue(
        rows.stream().anyMatch(row -> row.contains(ADMINISTRATOR)),
        () -> "no Administrator in " + rows);
    String juno = rowFor(rows, "juno.vale");
    assertTrue(
        juno.contains(AccountText.nameOf(SPANISH, Role.OPERATOR)), () -> "no Role in " + juno);
    assertTrue(
        juno.contains(AccountText.bandOf(SPANISH, Optional.of(PasswordStrength.WEAK))),
        () -> "no band in " + juno);
    assertTrue(
        juno.contains(
            AccountText.preferenceOf(SPANISH, Optional.of(Locale.forLanguageTag("es-ES")))),
        () -> "no language in " + juno);
    assertTrue(
        juno.contains(AccountText.lockoutOf(SPANISH, Optional.of(Duration.ofMinutes(10)))),
        () -> "no Lockout in " + juno);
  }

  /** An Account that has said nothing about a language is not shown as having chosen one. */
  @Test
  void anAccountWithNoLanguagePreferenceSaysSoRatherThanNamingOne() {
    openThePanel();

    String operator = rowFor(listedAccounts(), OPERATOR);

    assertTrue(
        operator.contains(AccountText.preferenceOf(SPANISH, Optional.empty())),
        () -> "a language nobody chose is claimed in " + operator);
  }

  /** Criterion 1's last column, and what criterion 5 is about clearing. */
  @Test
  void anAccountThatIsLockedOutIsListedAsLockedOut() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            Optional.of(PasswordStrength.ACCEPTABLE),
            Optional.empty(),
            Optional.of(Duration.ofMinutes(10))));
    openThePanel();

    String juno = rowFor(listedAccounts(), "juno.vale");

    assertTrue(
        juno.contains(AccountText.lockoutOf(SPANISH, Optional.of(Duration.ofMinutes(10)))),
        () -> "the Lockout is not on the screen: " + juno);
    assertNotEquals(
        AccountText.lockoutOf(SPANISH, Optional.of(Duration.ofMinutes(10))),
        AccountText.lockoutOf(SPANISH, Optional.empty()),
        "a locked Account must not read the same as one that is not");
  }

  /**
   * Story 72 asks an Administrator to find the Accounts worth nudging. One that has never had a
   * password is not one of them, so it must not read like the weakest band — which is what the
   * store holds for it, and deliberately.
   */
  @Test
  void anAccountAwaitingEnrolmentIsSaidToBeWaitingRatherThanShownABand() {
    gate.holding(
        new AccountSummary(
            "juno.vale", Role.OPERATOR, Optional.empty(), Optional.empty(), Optional.empty()));
    openThePanel();

    String juno = rowFor(listedAccounts(), "juno.vale");

    assertFalse(
        juno.contains(AccountText.bandOf(SPANISH, Optional.of(PasswordStrength.WEAK))),
        () -> "an Account with no password reads as a weak one: " + juno);
    assertTrue(
        juno.contains(AccountText.bandOf(SPANISH, Optional.empty())),
        () -> "it does not say what it is waiting for: " + juno);
  }

  /** Criterion 2: shown exactly once, and said so. */
  @Test
  void creatingAnOperatorShowsTheEnrolmentSecretOnceWithAWarning() {
    openThePanel();

    createAnOperatorNamed("juno.vale");

    await(() -> !secret().isBlank());
    assertTrue(
        gate.administrations().contains("createOperator:juno.vale"),
        () -> "the service was not asked: " + gate.administrations());
    String warning = textOf("#enrolmentSecretWarning").toLowerCase(Locale.ROOT);
    assertTrue(
        warning.contains("no se volverá a mostrar") || warning.contains("una sola vez"),
        () -> "the warning does not say it will not be shown again: " + warning);
  }

  @Test
  void theEnrolmentSecretIsGoneOnceTheAdministratorSaysTheyHaveWrittenItDown() {
    openThePanel();
    createAnOperatorNamed("juno.vale");
    await(() -> !secret().isBlank());
    String shown = secret();

    clickOn("#enrolmentSecretRead");

    await(() -> secret().isBlank());
    assertFalse(listedAccounts().toString().contains(shown), "the secret is not on the screen");
  }

  /**
   * Criterion 2's other half. There is no request on the LoginGate that reads a secret back — the
   * store keeps a hash of it — so what this asserts is that going on using the panel does not
   * produce it again either.
   */
  @Test
  void nothingAskedOfThePanelAfterwardsBringsTheSecretBack() {
    openThePanel();
    createAnOperatorNamed("juno.vale");
    await(() -> !secret().isBlank());
    clickOn("#enrolmentSecretRead");
    await(() -> secret().isBlank());

    select(OPERATOR);
    clickOn("#clearLockout");

    await(() -> gate.administrations().contains("clearTheLockoutOf:" + OPERATOR));
    assertTrue(secret().isBlank(), "the secret came back on the screen");
    assertTrue(
        gate.administrations().stream().filter(asked -> asked.startsWith("createOperator")).count()
            == 1,
        () -> "the panel asked for another secret: " + gate.administrations());
  }

  /** A name the AccountPolicy refuses is said in the rules it broke, not as a failure. */
  @Test
  void aNameTheServiceRefusesIsExplainedRuleByRule() {
    gate.refuseTheNameFor(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED));
    openThePanel();

    createAnOperatorNamed("admin");

    await(() -> !message().isBlank());
    assertEquals(
        PolicyViolationText.paragraphFor(SPANISH, List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED)),
        message());
    assertTrue(secret().isBlank(), "no secret comes of a refused name");
  }

  /** Criterion 3: the consequences are on the screen before anything is deleted. */
  @Test
  void deletingAnOperatorStatesWhatItCostsBeforeItHappens() {
    openThePanel();
    select(OPERATOR);

    clickOn("#deleteOperator");

    String consequences = textOf("#deleteConsequences").toLowerCase(Locale.ROOT);
    assertFalse(consequences.isBlank(), "nothing was said about what deleting costs");
    assertTrue(
        consequences.contains("no se puede deshacer") || consequences.contains("irreversible"),
        () -> "the consequences do not say it cannot be undone: " + consequences);
    assertTrue(
        gate.administrations().stream().noneMatch(asked -> asked.startsWith("deleteOperator")),
        () -> "the Account was deleted before anybody confirmed: " + gate.administrations());
  }

  @Test
  void theOperatorIsDeletedOnceItIsConfirmed() {
    openThePanel();
    select(OPERATOR);

    clickOn("#deleteOperator");
    clickOn("#confirmDelete");

    await(() -> listedAccounts().stream().noneMatch(row -> row.contains(OPERATOR)));
    assertTrue(
        gate.accountsHeld().stream().noneMatch(account -> account.name().equals(OPERATOR)),
        "the Account should be gone from the service too");
  }

  /** Criterion 4: the Administrator initiates it and never chooses what replaces the password. */
  @Test
  void aPasswordResetHandsBackASecretAndNeverAsksForAPassword() {
    openThePanel();
    select(OPERATOR);

    clickOn("#resetPassword");

    await(() -> !secret().isBlank());
    assertEquals(
        List.of("resetThePasswordOf:" + OPERATOR),
        gate.administrations().stream().filter(asked -> !asked.equals("accounts")).toList(),
        "the panel asks for a reset and asks for nothing else");
    // Issue #14 put one PasswordField on this screen, and this is where it is kept honest: the
    // only one is the Backup file's, which belongs to a file rather than to a person and admits
    // nobody. A second one appearing here would be the panel growing somewhere to type a
    // credential somebody goes on using, which is the thing story 21 forbids.
    assertEquals(
        List.of("backupPassword"),
        lookup(node -> node instanceof PasswordField).queryAll().stream()
            .map(Node::getId)
            .toList(),
        "there is nowhere on this panel to type an Account's password");
  }

  /**
   * The service records no PASSWORD_RESET_INITIATED for an Account that had no password to take
   * away, and the panel must not assert an event the service did not make.
   */
  @Test
  void reissuingASecretDoesNotClaimAPasswordStoppedWorking() {
    gate.holding(
        new AccountSummary(
            "juno.vale", Role.OPERATOR, Optional.empty(), Optional.empty(), Optional.empty()));
    openThePanel();
    select("juno.vale");

    clickOn("#resetPassword");

    await(() -> !message().isBlank());
    assertFalse(
        message().toLowerCase(Locale.ROOT).contains("contraseña de"),
        () -> "a password nobody had is said to have stopped working: " + message());
  }

  /** Criterion 5. */
  @Test
  void aLockoutCanBeCleared() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            Optional.of(PasswordStrength.ACCEPTABLE),
            Optional.empty(),
            Optional.of(Duration.ofMinutes(10))));
    openThePanel();
    select("juno.vale");

    clickOn("#clearLockout");

    await(() -> gate.administrations().contains("clearTheLockoutOf:juno.vale"));
    await(
        () ->
            gate.accountsHeld().stream()
                .filter(account -> account.name().equals("juno.vale"))
                .noneMatch(account -> account.lockedFor().isPresent()));
  }

  /** Criterion 6, first half. */
  @Test
  void theInactivityPeriodCanBeChanged() {
    openThePanel();

    write("#inactivityMinutes", "5");
    clickOn("#applyInactivityPeriod");

    await(() -> gate.configuredPeriod() != null);
    assertEquals(InactivityPeriod.of(Duration.ofMinutes(5)), gate.configuredPeriod());
  }

  /** Criterion 6, second half: a kiosk deployment. */
  @Test
  void expiryCanBeSwitchedOffEntirely() {
    openThePanel();

    clickOn("#neverExpires");
    clickOn("#applyInactivityPeriod");

    await(() -> gate.configuredPeriod() != null);
    assertTrue(gate.configuredPeriod().isDisabled(), "expiry should be switched off");
  }

  @Test
  void aPeriodThatIsNotANumberOfMinutesChangesNothingAndSaysSo() {
    openThePanel();

    write("#inactivityMinutes", "un rato");
    clickOn("#applyInactivityPeriod");

    assertFalse(message().isBlank(), "nothing was said about what was typed");
    assertEquals(null, gate.configuredPeriod(), "nothing should have been configured");
  }

  /** Criterion 7. */
  @Test
  void theRecordCanBeExportedAndSaysWhatTheCopyCameTo() {
    openThePanel();

    write("#exportDestination", "/tmp/eventos.csv");
    clickOn("#export");

    await(() -> gate.exportedTo() != null);
    assertEquals(Path.of("/tmp/eventos.csv"), gate.exportedTo());
    await(() -> message().contains("12"));
  }

  /** The one thing an export says that somebody has to act on. */
  @Test
  void anExportWhoseChainDidNotHoldSaysSoInItsOwnWords() {
    gate.exportsComeTo(new AuthenticationEventExport(12, false));
    openThePanel();

    write("#exportDestination", "/tmp/eventos.csv");
    clickOn("#export");

    await(() -> !message().isBlank());
    assertTrue(
        message().toLowerCase(Locale.ROOT).contains("cadena")
            || message().toLowerCase(Locale.ROOT).contains("integridad"),
        () -> "an edited record is not reported: " + message());
  }

  /**
   * Issue #14, criterion 1 at this seam: the panel asks for a Backup with the path and the password
   * that were typed for it, and says what the file came to.
   */
  @Test
  void aBackupIsWrittenWithThePasswordTypedForIt() {
    gate.backupsComeTo(new Backup(7, 5));
    openThePanel();

    write("#backupFile", "/tmp/copia.jflb");
    write("#backupPassword", "Copia-Caballo-7");
    clickOn("#exportBackup");

    await(() -> gate.backedUpTo() != null);
    assertEquals(Path.of("/tmp/copia.jflb"), gate.backedUpTo());
    assertEquals("Copia-Caballo-7", gate.backupPassword());
    await(() -> message().contains("7") && message().contains("5"));
  }

  /** A file with no password to seal it is not written, and the panel says which half is missing. */
  @Test
  void aBackupIsNotWrittenWithoutAPasswordForTheFile() {
    openThePanel();

    write("#backupFile", "/tmp/copia.jflb");
    clickOn("#exportBackup");

    assertFalse(message().isBlank(), "nothing was said about the missing password");
    assertEquals(null, gate.backedUpTo(), "a Backup was asked for without a password");
  }

  /**
   * Issue #14, criterion 6: what an import destroys is on the screen before anything is destroyed,
   * and the import itself is a second, separate click.
   */
  @Test
  void restoringABackupStatesWhatItDestroysBeforeItHappens() {
    openThePanel();

    write("#backupFile", "/tmp/copia.jflb");
    write("#backupPassword", "Copia-Caballo-7");
    clickOn("#importBackup");

    String consequences = textOf("#importConsequences").toLowerCase(Locale.ROOT);
    assertFalse(consequences.isBlank(), "nothing was said about what restoring destroys");
    assertTrue(
        consequences.contains("no se puede deshacer"),
        () -> "the warning does not say it cannot be undone: " + consequences);
    assertTrue(
        gate.administrations().stream().noneMatch(asked -> asked.startsWith("importBackupFrom")),
        () -> "the store was replaced before anybody confirmed: " + gate.administrations());
  }

  /** Cancelling leaves the warning off the screen and the deployment where it was. */
  @Test
  void anImportThatIsCancelledAsksTheServiceForNothing() {
    openThePanel();

    write("#backupFile", "/tmp/copia.jflb");
    write("#backupPassword", "Copia-Caballo-7");
    clickOn("#importBackup");
    clickOn("#cancelImport");

    assertTrue(textOf("#importConsequences").isBlank(), "the warning is still on the screen");
    assertTrue(
        gate.administrations().stream().noneMatch(asked -> asked.startsWith("importBackupFrom")),
        () -> "the store was replaced by a cancel: " + gate.administrations());
  }

  /**
   * And once it is confirmed the panel is over, because the Session it ran under named an Account in
   * a deployment that has just been replaced. The person is handed back to the login screen of the
   * one they restored.
   */
  @Test
  void confirmingTheImportRestoresTheBackupAndHandsThePersonBackToTheLoginScreen() {
    openThePanel();

    write("#backupFile", "/tmp/copia.jflb");
    write("#backupPassword", "Copia-Caballo-7");
    clickOn("#importBackup");
    clickOn("#confirmImport");

    await(() -> gate.restoredFrom() != null);
    assertEquals(Path.of("/tmp/copia.jflb"), gate.restoredFrom());
    assertEquals("Copia-Caballo-7", gate.backupPassword());
    awaitTheLoginScreen();
  }

  /** A refusal is said and nothing else happens, as every other refusal on this panel is. */
  @Test
  void aBackupTheServiceWillNotOpenIsSaidRatherThanSwallowed() {
    openThePanel();
    gate.refuseAdministrationWith(AdministrationRefusedReason.BACKUP_NOT_READ);

    write("#backupFile", "/tmp/copia.jflb");
    write("#backupPassword", "Copia-Caballo-7");
    clickOn("#importBackup");
    clickOn("#confirmImport");

    await(() -> !message().isBlank());
    assertEquals(
        SPANISH.say(AdministrationRefusedText.keyFor(AdministrationRefusedReason.BACKUP_NOT_READ)),
        message());
    assertTrue(lookup("#accounts").tryQuery().isPresent(), "the panel should still be open");
  }

  /** Criterion 8: present, visibly disabled, and doing nothing. */
  @Test
  void theSecondFactorControlIsThereAndDisabled() {
    openThePanel();

    CheckBox secondFactor = lookup("#secondFactor").queryAs(CheckBox.class);

    assertTrue(secondFactor.isVisible(), "the seam should be visible");
    assertTrue(secondFactor.isDisabled(), "and it should do nothing in this version");
    assertFalse(secondFactor.isSelected(), "nothing implements it");
  }

  /** A refusal by the service is the panel's answer, not a screen that pretends it worked. */
  @Test
  void whatTheServiceRefusesIsSaidRatherThanSwallowed() {
    openThePanel();
    gate.refuseAdministrationWith(AdministrationRefusedReason.CANNOT_DELETE_THE_ADMINISTRATOR);
    select(ADMINISTRATOR);

    clickOn("#deleteOperator");
    clickOn("#confirmDelete");

    await(() -> !message().isBlank());
    assertTrue(
        listedAccounts().stream().anyMatch(row -> row.contains(ADMINISTRATOR)),
        "the Administrator is still there");
  }

  /**
   * Issue #13's last criterion: an Administrator says which language somebody reads the interface
   * in. It is recorded against that Account and takes effect at their next admission — nothing on
   * this panel changes language because of it, and the list says what was recorded.
   */
  @Test
  void anAdministratorSaysWhichLanguageAnAccountReads() {
    openThePanel();
    select(OPERATOR);

    chooseTheLanguage(ENGLISH);
    clickOn("#applyLanguage");

    await(() -> gate.administrations().contains("useLanguagePreference:" + OPERATOR + ":en"));
    await(
        () ->
            rowFor(listedAccounts(), OPERATOR)
                .contains(AccountText.preferenceOf(SPANISH, Optional.of(ENGLISH))));
    assertTrue(
        message().contains(InterfaceLanguage.nameOf(ENGLISH)),
        () -> "the panel should say which language: " + message());
  }

  /** The other direction: an Account goes back to following whichever machine it is used on. */
  @Test
  void anAccountIsPutBackToFollowingTheMachine() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            Optional.of(PasswordStrength.ACCEPTABLE),
            Optional.of(ENGLISH)));
    openThePanel();
    select("juno.vale");

    chooseTheLanguage(null);
    clickOn("#applyLanguage");

    await(
        () ->
            gate.administrations().contains("useLanguagePreference:juno.vale:the machine's"));
    await(
        () ->
            rowFor(listedAccounts(), "juno.vale")
                .contains(AccountText.preferenceOf(SPANISH, Optional.empty())));
  }

  /** The selector says what the chosen Account holds, and not what the last one held. */
  @Test
  void choosingAnAccountShowsTheLanguageItReads() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            Optional.of(PasswordStrength.ACCEPTABLE),
            Optional.of(ENGLISH)));
    openThePanel();

    select("juno.vale");
    assertEquals(ENGLISH, languageChoice().getValue());

    select(OPERATOR);
    assertEquals(
        Locale.ROOT,
        languageChoice().getValue(),
        "an Account that has said nothing follows the machine");
  }

  /** Nothing is proposed about somebody's language until somebody is chosen. */
  @Test
  void aLanguageCannotBeSetWithoutChoosingAnAccountFirst() {
    openThePanel();

    chooseTheLanguage(ENGLISH);
    clickOn("#applyLanguage");

    await(() -> !message().isEmpty());
    assertFalse(
        gate.administrations().stream()
            .anyMatch(asked -> asked.startsWith("useLanguagePreference")),
        () -> "the service was asked anyway: " + gate.administrations());
  }

  /** The panel is a window over a Session: when the Session ends, it goes, like every other one. */
  @Test
  void theWindowClosesAndTheLoginScreenReturnsWhenTheSessionEnds() {
    gate.sessionsLastFor(Duration.ofMillis(100));
    openThePanel();

    gate.theSessionEnds(SessionEndedReason.INACTIVITY);

    awaitTheLoginScreen();
    await(() -> lookup("#accounts").tryQuery().isEmpty());
  }

  @Test
  void theAdministratorCanLeaveDeliberately() {
    openThePanel();

    clickOn("#logOut");

    awaitTheLoginScreen();
    assertEquals(1, gate.logouts(), "the service should have been told");
  }

  /** Story 37: the same screen, one control apart — and the panel is what it opens. */
  private void openThePanel() {
    clickOn("#accountName").write(ADMINISTRATOR);
    clickOn("#password").write(ADMINISTRATOR_PASSWORD);
    clickOn("#administer");
    clickOn("#admit");
    await(() -> lookup("#accounts").tryQuery().isPresent());
    awaitTheAccounts();
  }

  private void createAnOperatorNamed(String accountName) {
    clickOn("#newOperatorName").write(accountName);
    clickOn("#createOperator");
  }

  /** Puts the selector on a language, or on the machine's own where none is named. */
  private void chooseTheLanguage(Locale language) {
    interact(() -> languageChoice().setValue(language == null ? Locale.ROOT : language));
  }

  @SuppressWarnings("unchecked")
  private ComboBox<Locale> languageChoice() {
    return (ComboBox<Locale>) lookup("#languageChoice").queryAs(ComboBox.class);
  }

  private void select(String accountName) {
    TableView<AccountSummary> table = table();
    interact(
        () -> {
          for (int row = 0; row < table.getItems().size(); row++) {
            if (table.getItems().get(row) instanceof AccountSummary account
                && account.name().equals(accountName)) {
              table.getSelectionModel().select(row);
              return;
            }
          }
          throw new AssertionError("no row for " + accountName + " in " + table.getItems());
        });
  }

  private void write(String field, String text) {
    clickOn(field).write(text);
  }

  /**
   * Every row as the columns show it, rather than as the record prints itself: what is asserted
   * here is what a person reads off the screen.
   */
  private List<String> listedAccounts() {
    TableView<AccountSummary> table = table();
    return table.getItems().stream().map(account -> rowOf(table, account)).toList();
  }

  private static String rowOf(TableView<AccountSummary> table, AccountSummary account) {
    int row = table.getItems().indexOf(account);
    return table.getColumns().stream()
        .map(column -> String.valueOf(column.getCellObservableValue(row).getValue()))
        .collect(Collectors.joining(" | "));
  }

  @SuppressWarnings("unchecked")
  private TableView<AccountSummary> table() {
    return (TableView<AccountSummary>) lookup("#accounts").queryAs(TableView.class);
  }

  private static String rowFor(List<String> rows, String accountName) {
    return rows.stream()
        .filter(row -> row.contains(accountName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + accountName + " in " + rows));
  }

  private String secret() {
    return textOf("#enrolmentSecret");
  }

  private String message() {
    return textOf("#message");
  }

  private String textOf(String id) {
    Node node = lookup(id).query();
    return node instanceof Label label && label.getText() != null ? label.getText() : "";
  }

  private void awaitTheAccounts() {
    await(() -> !table().getItems().isEmpty());
  }

  private void awaitTheLoginScreen() {
    await(() -> lookup("#admit").tryQuery().isPresent());
  }

  private void await(BooleanSupplier until) {
    try {
      WaitForAsyncUtils.waitFor(PATIENCE_IN_SECONDS, TimeUnit.SECONDS, until::getAsBoolean);
      WaitForAsyncUtils.waitForFxEvents();
    } catch (TimeoutException e) {
      throw new AssertionError("the window never got there", e);
    }
  }
}
