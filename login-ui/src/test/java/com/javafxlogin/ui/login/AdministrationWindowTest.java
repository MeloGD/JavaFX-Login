package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventExport;
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
import java.util.stream.Collectors;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";

  private static final int PATIENCE_IN_SECONDS = 10;

  private FakeLoginGate gate;
  private Stage loginStage;

  @Override
  public void start(Stage stage) {
    loginStage = stage;
    gate =
        new FakeLoginGate()
            .administeredBy(ADMINISTRATOR, ADMINISTRATOR_PASSWORD)
            .holdingTheOperator(OPERATOR);
    gate.protect(stage, session -> theHostsView());
  }

  /** The host product's view, which an Administrator must never be handed. */
  private static Parent theHostsView() {
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

    assertTrue(lookup("#feature").tryQuery().isEmpty(), "the feature must not be on the screen");
    assertTrue(lookup("#accounts").tryQuery().isPresent(), "the panel should be");
  }

  /** Criterion 1: the Role, the coarse band, the language preference and the Lockout. */
  @Test
  void everyAccountIsListedWithWhatTheAdministratorNeedsToKnowAboutIt() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            PasswordStrength.WEAK,
            Optional.of(Locale.forLanguageTag("es-ES")),
            Optional.of(Duration.ofMinutes(10))));
    openThePanel();

    List<String> rows = listedAccounts();

    assertTrue(rows.stream().anyMatch(row -> row.contains(OPERATOR)), () -> "no Operator in " + rows);
    assertTrue(
        rows.stream().anyMatch(row -> row.contains(ADMINISTRATOR)),
        () -> "no Administrator in " + rows);
    String juno = rowFor(rows, "juno.vale");
    assertTrue(juno.contains(AccountText.nameOf(Role.OPERATOR)), () -> "no Role in " + juno);
    assertTrue(
        juno.contains(AccountText.bandOf(PasswordStrength.WEAK)), () -> "no band in " + juno);
    assertTrue(
        juno.contains(AccountText.languageOf(Optional.of(Locale.forLanguageTag("es-ES")))),
        () -> "no language in " + juno);
    assertTrue(
        juno.contains(AccountText.lockoutOf(Optional.of(Duration.ofMinutes(10)))),
        () -> "no Lockout in " + juno);
  }

  /** An Account that has said nothing about a language is not shown as having chosen one. */
  @Test
  void anAccountWithNoLanguagePreferenceSaysSoRatherThanNamingOne() {
    openThePanel();

    String operator = rowFor(listedAccounts(), OPERATOR);

    assertTrue(
        operator.contains(AccountText.languageOf(Optional.empty())),
        () -> "a language nobody chose is claimed in " + operator);
  }

  /** Criterion 1's last column, and what criterion 5 is about clearing. */
  @Test
  void anAccountThatIsLockedOutIsListedAsLockedOut() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            PasswordStrength.ACCEPTABLE,
            Optional.empty(),
            Optional.of(Duration.ofMinutes(10))));
    openThePanel();

    String juno = rowFor(listedAccounts(), "juno.vale");

    assertTrue(
        juno.contains(AccountText.lockoutOf(Optional.of(Duration.ofMinutes(10)))),
        () -> "the Lockout is not on the screen: " + juno);
    assertNotEquals(
        AccountText.lockoutOf(Optional.of(Duration.ofMinutes(10))),
        AccountText.lockoutOf(Optional.empty()),
        "a locked Account must not read the same as one that is not");
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

  /** A name the AccountPolicy refuses is said in the rules it broke, not as a failure. */
  @Test
  void aNameTheServiceRefusesIsExplainedRuleByRule() {
    gate.refuseTheNameFor(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED));
    openThePanel();

    createAnOperatorNamed("admin");

    await(() -> !message().isBlank());
    assertEquals(
        PolicyViolationText.paragraphFor(List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED)), message());
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
    assertTrue(
        lookup(node -> node instanceof PasswordField).queryAll().isEmpty(),
        "there is nowhere on this panel to type somebody's password");
  }

  /** Criterion 5. */
  @Test
  void aLockoutCanBeCleared() {
    gate.holding(
        new AccountSummary(
            "juno.vale",
            Role.OPERATOR,
            PasswordStrength.ACCEPTABLE,
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
                .noneMatch(AccountSummary::isLockedOut));
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
