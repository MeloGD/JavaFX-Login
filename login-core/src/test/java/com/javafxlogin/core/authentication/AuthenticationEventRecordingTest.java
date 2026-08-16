package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEvent;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.harness.StubConnection;
import com.javafxlogin.core.ipc.AuthenticationEventsExported;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.ExportAuthenticationEvents;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Logout;
import com.javafxlogin.core.ipc.PolicyRefused;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.ipc.SessionEnded;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: what the AuthenticationService writes down, and the one way it is ever read back.
 *
 * <p>Stories 73 to 81. The record is what ADR-0005 leans on — reaching the SecretVault as an
 * Administrator means creating and enrolling an Account, and those land here — so what is asserted
 * is as much what never reaches it as what does.
 */
class AuthenticationEventRecordingTest {

  private static final String ADMINISTRATOR = "wren.holloway";
  private static final String ADMINISTRATOR_PASSWORD = "Correct-Horse-1";
  private static final String OPERATOR = "finch.mercer";
  private static final String OPERATOR_PASSWORD = "Another-Horse-2";
  private static final String WRONG_PASSWORD = "Wrong-Horse-9";

  /** What V004 writes: the number of failures in a row that locks an Account. */
  private static final int FAILURES_THAT_LOCK = 5;

  /** The store's directory, which is also where the record lives. */
  @TempDir Path directory;

  /** Somewhere an export may go, since the service refuses to write inside its own directory. */
  @TempDir Path elsewhere;

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

  // --- what is recorded ----------------------------------------------------------------------

  /** Story 73: the Account change this build can make is the one the FirstRunWizard makes. */
  @Test
  void creatingTheAdministratorIsRecorded() throws IOException {
    assertRecorded("ADMINISTRATOR_CREATED", ADMINISTRATOR);
  }

  @Test
  void aSuccessfulAuthenticationIsRecordedAgainstTheAccount() throws IOException {
    admitTheOperator();

    assertRecorded("AUTHENTICATION_SUCCEEDED", OPERATOR);
  }

  @Test
  void aWrongPasswordIsRecordedAgainstTheAccountItWasOfferedTo() throws IOException {
    attempt(OPERATOR, WRONG_PASSWORD, Role.OPERATOR);

    assertRecorded("AUTHENTICATION_FAILED_WRONG_PASSWORD", OPERATOR);
  }

  /**
   * The record says which of the two it was, where the login screen may not be told. Whoever
   * exports this has already proved they administer the deployment.
   */
  @Test
  void theRightPasswordInTheWrongRoleIsRecordedAsSuch() throws IOException {
    attempt(ADMINISTRATOR, ADMINISTRATOR_PASSWORD, Role.OPERATOR);

    assertRecorded("AUTHENTICATION_FAILED_WRONG_ROLE", ADMINISTRATOR);
  }

  @Test
  void beingRefusedWhileLockedOutIsRecorded() throws IOException {
    lockOutTheOperator();

    attempt(OPERATOR, WRONG_PASSWORD, Role.OPERATOR);

    assertRecorded("AUTHENTICATION_REFUSED_LOCKED_OUT", OPERATOR);
  }

  /** Refused before any Account is looked at, so there is nobody to record it against. */
  @Test
  void anAttemptMadeWhileTheMachineIsBusyIsRecordedAgainstNobody() throws IOException {
    admitTheOperator();

    attempt(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR);

    assertRecorded("AUTHENTICATION_REFUSED_SESSION_ALREADY_LIVE", AuthenticationEvent.NO_ACCOUNT);
  }

  // --- what never reaches it -----------------------------------------------------------------

  /** Story 77: the name box is where a password eventually gets typed. */
  @Test
  void anAttemptAgainstANameNobodyHoldsIsRecordedAgainstAPlaceholder() throws IOException {
    attempt("Another-Horse-2-typed-in-the-wrong-box", WRONG_PASSWORD, Role.OPERATOR);

    assertRecorded("AUTHENTICATION_FAILED_NO_SUCH_ACCOUNT", AuthenticationEvent.NO_ACCOUNT);
    assertFalse(
        recordedEvents().contains("typed-in-the-wrong-box"), "the typed name reached the record");
  }

  /** Which is only worth anything if nobody can be provisioned under the placeholder. */
  @Test
  void noAccountCanBeCalledWhatStandsInForOneThatIsMissing() {
    try (ServiceHarness fresh = ServiceHarness.cheap(elsewhere)) {
      Response response = fresh.bootstrap(AuthenticationEvent.NO_ACCOUNT, ADMINISTRATOR_PASSWORD);

      assertTrue(
          assertInstanceOf(PolicyRefused.class, response)
              .violations()
              .contains(PolicyViolation.ACCOUNT_NAME_BLOCKED));
    }
  }

  /** Story 73 wants the attempt recorded, not what was offered at it. */
  @Test
  void noPasswordEverReachesTheRecord() throws IOException {
    admitTheOperator();
    attempt(OPERATOR, WRONG_PASSWORD, Role.OPERATOR);

    String recorded = recordedEvents();
    assertFalse(recorded.contains(OPERATOR_PASSWORD), "a password reached the record");
    assertFalse(recorded.contains(WRONG_PASSWORD), "a password reached the record");
    assertFalse(recorded.contains(ADMINISTRATOR_PASSWORD), "a password reached the record");
  }

  @Test
  void noSessionTokenEverReachesTheRecord() throws IOException {
    SessionToken token = admitTheOperator();

    String asItCrossesTheWire = Base64.getEncoder().encodeToString(token.copyOfBytes());
    assertFalse(recordedEvents().contains(asItCrossesTheWire), "the token reached the record");
  }

  /** Story 81: a record that cannot be written is a gap, never an outage. */
  @Test
  void authenticationStillSucceedsWhenTheRecordCannotBeWritten() throws IOException {
    Path log = ServiceHarness.eventLogIn(directory);
    Files.delete(log);
    Files.createDirectory(log);

    assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR));
  }

  // --- the one way it is read ----------------------------------------------------------------

  /** Story 75, and story 74: what comes back is a file and two numbers, never an event. */
  @Test
  void anAdministratorCanExportTheRecord() throws IOException {
    Path export = elsewhere.resolve("authentication-events-export.csv");

    AuthenticationEventsExported exported =
        assertInstanceOf(AuthenticationEventsExported.class, exportTo(export));

    assertTrue(exported.export().chainIntact(), "the record reported itself edited");
    assertTrue(exported.export().events() > 0, "an empty export of a record that has entries");
    assertTrue(Files.readString(export).contains("ADMINISTRATOR_CREATED"));
  }

  @Test
  void exportingIsItselfRecorded() throws IOException {
    exportTo(elsewhere.resolve("authentication-events-export.csv"));

    assertRecorded("AUTHENTICATION_EVENTS_EXPORTED", ADMINISTRATOR);
  }

  /** The copy is as unreadable to an unprivileged account as what it was copied from. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void theExportIsOwnerOnly() throws IOException {
    Path export = elsewhere.resolve("authentication-events-export.csv");

    exportTo(export);

    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(export));
  }

  @Test
  void anOperatorCannotExportTheRecord() {
    SessionToken operator = admitTheOperator();

    Response response =
        harness.send(new ExportAuthenticationEvents(operator, elsewhere.resolve("export.csv")));

    assertEquals(new ErrorResponse(ErrorCode.NOT_ADMINISTRATOR), response);
  }

  @Test
  void aTokenThatNamesNoSessionExportsNothing() {
    Response response =
        harness.send(
            new ExportAuthenticationEvents(
                SessionToken.generate(new SecureRandom()), elsewhere.resolve("export.csv")));

    assertEquals(new SessionEnded(SessionEndedReason.NO_SUCH_SESSION), response);
  }

  /** A working directory the person asking cannot see is not somewhere to put a copy. */
  @Test
  void aRelativeDestinationIsRefused() {
    assertEquals(refused(), exportTo(Path.of("export.csv")));
  }

  /** Nothing an export writes may land on the store, the record, or the key it is chained under. */
  @Test
  void aDestinationInsideTheServicesOwnDirectoryIsRefused() {
    assertEquals(refused(), exportTo(directory.resolve("export.csv")));
    assertEquals(refused(), exportTo(ServiceHarness.eventLogIn(directory)));
  }

  @Test
  void aDestinationInADirectoryThatIsNotThereIsRefused() {
    assertEquals(refused(), exportTo(elsewhere.resolve("nowhere").resolve("export.csv")));
  }

  @Test
  void aDestinationThatIsAlreadyThereIsRefused() throws IOException {
    Path export = Files.writeString(elsewhere.resolve("export.csv"), "something else");

    assertEquals(refused(), exportTo(export));
    assertEquals("something else", Files.readString(export));
  }

  // --- getting there -------------------------------------------------------------------------

  private static ErrorResponse refused() {
    return new ErrorResponse(ErrorCode.EXPORT_DESTINATION_REFUSED);
  }

  /**
   * Exports as the Administrator would, over a connection of its own so that the Operator Session
   * some tests hold is not the one asking, and hands the machine back afterwards so that a test can
   * ask twice.
   */
  private Response exportTo(Path destination) {
    StubConnection administrators = harness.anotherConnection();
    SessionToken token =
        assertInstanceOf(
                Granted.class,
                harness.sendOver(
                    administrators,
                    new Authenticate(
                        ADMINISTRATOR,
                        ADMINISTRATOR_PASSWORD.toCharArray(),
                        Role.ADMINISTRATOR)))
            .token();
    Response response =
        harness.sendOver(administrators, new ExportAuthenticationEvents(token, destination));
    harness.sendOver(administrators, new Logout(token));
    return response;
  }

  private SessionToken admitTheOperator() {
    return assertInstanceOf(Granted.class, attempt(OPERATOR, OPERATOR_PASSWORD, Role.OPERATOR))
        .token();
  }

  private Response attempt(String accountName, String password, Role role) {
    return harness.send(new Authenticate(accountName, password.toCharArray(), role));
  }

  private void lockOutTheOperator() {
    Response denied = null;
    for (int failure = 0; failure < FAILURES_THAT_LOCK; failure++) {
      denied = attempt(OPERATOR, WRONG_PASSWORD, Role.OPERATOR);
    }
    assertTrue(
        assertInstanceOf(Denied.class, denied).lockedFor().isPresent(),
        "the Operator was meant to be locked out by now");
  }

  private void assertRecorded(String type, String subject) throws IOException {
    String recorded = recordedEvents();
    assertTrue(
        recorded.lines().anyMatch(line -> line.contains(type) && line.contains(subject)),
        () -> "no " + type + " about " + subject + " in:\n" + recorded);
  }

  private String recordedEvents() throws IOException {
    Path log = ServiceHarness.eventLogIn(directory);
    return Files.exists(log) ? Files.readString(log) : "";
  }
}
