package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventType;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AccountsListed;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.ChangeLanguagePreference;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.ListAccounts;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the LanguagePreference an Account holds — who may record one, and who is told about it.
 *
 * <p>Issue #13's half that lives in the privileged process, which is deliberately small. The
 * bundles, the selector and the wording are all the client's; what the service owns is the fact
 * itself: it is written by an Administrator, it belongs to an Account, and it is handed back on the
 * admission that proves somebody holds that Account and nowhere else. A client can therefore not
 * learn which language an Account reads by asking about a name it does not hold.
 */
class LanguagePreferenceTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";

  private static final Locale SPANISH = Locale.forLanguageTag("es");

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openServiceWithBothRoles() {
    harness = ServiceHarness.cheap(directory);
    harness.bootstrap(ADMINISTRATOR, ADMINISTRATOR_PASSWORD);
    harness.provisionOperator(OPERATOR, OPERATOR_PASSWORD);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void anAdministratorRecordsWhichLanguageAnAccountReads() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    Response response =
        harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH)));

    assertInstanceOf(Ok.class, response);
    assertEquals(Optional.of(SPANISH), listedPreferenceOf(administrator, OPERATOR));
  }

  /**
   * The other direction, which is a preference of its own rather than the absence of one: this
   * Account follows whichever machine it is read on, and that is what the panel goes back to
   * saying.
   */
  @Test
  void anAdministratorPutsAnAccountBackToFollowingTheMachine() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH)));

    Response response =
        harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.empty()));

    assertInstanceOf(Ok.class, response);
    assertEquals(Optional.empty(), listedPreferenceOf(administrator, OPERATOR));
  }

  /**
   * Which languages exist is the client's business. A service that refused a tag no bundle answered
   * to would have to be changed — and restarted, privileged — every time a language was added,
   * which is the change of shape issue #13 exists to avoid.
   */
  @Test
  void aLanguageThisBuildShipsNoWordingForIsRecordedAllTheSame() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    Response response =
        harness.send(
            new ChangeLanguagePreference(
                administrator, OPERATOR, Optional.of(Locale.forLanguageTag("eu"))));

    assertInstanceOf(Ok.class, response);
    assertEquals(
        Optional.of(Locale.forLanguageTag("eu")), listedPreferenceOf(administrator, OPERATOR));
  }

  /**
   * The language somebody reads is theirs to be given and not theirs to take: an Operator asking is
   * refused in the privileged process, so a patched client that drew the control anyway would be
   * drawing a control that changes nothing.
   */
  @Test
  void anOperatorIsRefusedTheChange() {
    SessionToken operator = admit(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    Response response =
        harness.send(new ChangeLanguagePreference(operator, OPERATOR, Optional.of(SPANISH)));

    assertEquals(
        ErrorCode.NOT_ADMINISTRATOR, assertInstanceOf(ErrorResponse.class, response).code());
  }

  /**
   * A name no Account holds is said plainly rather than answered with a cheerful Ok, as clearing a
   * Lockout is: an Administrator who mistyped it would otherwise walk away believing somebody's
   * screens had changed language.
   */
  @Test
  void aNameNoAccountHoldsIsRefusedRatherThanQuietlyAccepted() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    Response response =
        harness.send(
            new ChangeLanguagePreference(administrator, "nobody.here", Optional.of(SPANISH)));

    assertEquals(ErrorCode.NO_SUCH_ACCOUNT, assertInstanceOf(ErrorResponse.class, response).code());
  }

  @Test
  void aSessionThatIsOverIsToldSoRatherThanChangingAnything() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new Logout(administrator));

    assertInstanceOf(
        SessionEnded.class,
        harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH))));
  }

  /** An Account change, recorded against the Account it was about, like every other one. */
  @Test
  void changingWhichLanguageAnAccountReadsIsRecorded() throws IOException {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH)));

    String record = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(
        record.contains(AuthenticationEventType.LANGUAGE_PREFERENCE_CHANGED.name()),
        () -> "the change should be in the record: " + record);
    assertTrue(record.contains(OPERATOR), () -> "recorded against the Account: " + record);
  }

  /** What the record does not carry: which language it was changed to. */
  @Test
  void theRecordDoesNotSayWhichLanguageWasChosen() throws IOException {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH)));

    String record = Files.readString(ServiceHarness.eventLogIn(directory));
    assertTrue(
        record.lines().noneMatch(line -> line.contains(SPANISH.toLanguageTag())),
        () -> "the language reached the record: " + record);
  }

  /**
   * The admission is where a language preference is answered, because it is the first moment the
   * service knows whose preference to answer with. Before it there is a name somebody typed.
   */
  @Test
  void anAdmissionCarriesTheLanguageTheAccountReads() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new ChangeLanguagePreference(administrator, OPERATOR, Optional.of(SPANISH)));
    harness.send(new Logout(administrator));

    Granted granted = admissionOf(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    assertEquals(Optional.of(SPANISH), granted.languagePreference());
  }

  /** An Account that has said nothing says nothing here either, rather than naming a language. */
  @Test
  void anAdmissionOfAnAccountThatHasSaidNothingCarriesNoLanguage() {
    Granted granted = admissionOf(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    assertEquals(Optional.empty(), granted.languagePreference());
  }

  /** An Administrator reads the panel in their own language too. */
  @Test
  void anAdministratorsOwnAdmissionCarriesTheirLanguage() {
    SessionToken administrator = admit(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);
    harness.send(new ChangeLanguagePreference(administrator, ADMINISTRATOR, Optional.of(SPANISH)));
    harness.send(new Logout(administrator));

    Granted granted = admissionOf(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.ADMINISTRATOR);

    assertEquals(Optional.of(SPANISH), granted.languagePreference());
  }

  private Optional<Locale> listedPreferenceOf(SessionToken administrator, String accountName) {
    List<AccountSummary> accounts =
        assertInstanceOf(AccountsListed.class, harness.send(new ListAccounts(administrator)))
            .accounts();
    return accounts.stream()
        .filter(account -> account.name().equals(accountName))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no Account named " + accountName + " in " + accounts))
        .languagePreference();
  }

  private Granted admissionOf(String accountName, String password, Role role) {
    Response response = harness.send(new Authenticate(accountName, password.toCharArray(), role));
    return assertInstanceOf(Granted.class, response);
  }

  private SessionToken admit(String accountName, String password, Role role) {
    return admissionOf(accountName, password, role).token();
  }
}
