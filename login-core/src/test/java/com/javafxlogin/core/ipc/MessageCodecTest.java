package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventExport;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the messages look like on the wire, and what the parser refuses to read. */
class MessageCodecTest {

  @Test
  void carriesABootstrapUnchanged() {
    Bootstrap sent = new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray());

    Bootstrap received = (Bootstrap) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertEquals(sent.administratorName(), received.administratorName());
    assertArrayEquals(sent.password(), received.password());
  }

  @Test
  void carriesAnAuthenticateUnchanged() {
    Authenticate sent =
        new Authenticate("wren.holloway", "Correct-Horse-1".toCharArray(), Role.OPERATOR);

    Authenticate received = (Authenticate) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertEquals(sent.accountName(), received.accountName());
    assertArrayEquals(sent.password(), received.password());
    assertEquals(Role.OPERATOR, received.requestedRole());
  }

  @Test
  void carriesAnAssessUnchanged() {
    Assess sent = new Assess("wren.holloway", "short".toCharArray());

    Assess received = (Assess) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertEquals(sent.accountName(), received.accountName());
    assertArrayEquals(sent.password(), received.password());
  }

  @Test
  void carriesAnAskIfBootstrapNeededUnchanged() {
    assertEquals(
        new AskIfBootstrapNeeded(),
        MessageCodec.decodeRequest(MessageCodec.encode(new AskIfBootstrapNeeded())));
  }

  @Test
  void carriesABootstrapNeededUnchangedInBothAnswers() {
    assertEquals(
        new BootstrapNeeded(true),
        MessageCodec.decodeResponse(MessageCodec.encode(new BootstrapNeeded(true))));
    assertEquals(
        new BootstrapNeeded(false),
        MessageCodec.decodeResponse(MessageCodec.encode(new BootstrapNeeded(false))));
  }

  /**
   * A flag is a JSON boolean and nothing else. Reading {@code "false"} as a boolean would have this
   * codec guess, and a guess here is a client told the wrong window to open.
   */
  @Test
  void refusesABootstrapNeededWhoseAnswerIsNotABoolean() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes("{\"type\":\"BootstrapNeeded\",\"needed\":\"false\"}")));
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"BootstrapNeeded\"}")));
  }

  @Test
  void carriesEverySessionRequestsTokenByteForByte() {
    SessionToken token = SessionToken.generate(new SecureRandom());

    assertArrayEquals(token.copyOfBytes(), tokenOfRoundTripped(new ReportActivity(token)));
    assertArrayEquals(token.copyOfBytes(), tokenOfRoundTripped(new AskIfSessionIsLive(token)));
    assertArrayEquals(token.copyOfBytes(), tokenOfRoundTripped(new Logout(token)));
    assertArrayEquals(
        token.copyOfBytes(), tokenOfRoundTripped(new AcknowledgePasswordReset(token)));
    assertArrayEquals(token.copyOfBytes(), tokenOfRoundTripped(new ReadSecret(token, "a.secret")));
    assertArrayEquals(token.copyOfBytes(), tokenOfRoundTripped(new ListAccounts(token)));
    assertArrayEquals(
        token.copyOfBytes(),
        tokenOfRoundTripped(
            new ChangeOwnPassword(token, "Correct-Horse-1".toCharArray(), "B".toCharArray())));
  }

  @Test
  void carriesAReadSecretUnchanged() {
    ReadSecret sent =
        new ReadSecret(SessionToken.generate(new SecureRandom()), "warehouse.database.password");

    ReadSecret received = (ReadSecret) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.name(), received.name());
  }

  @Test
  void carriesAKeepSecretUnchanged() {
    KeepSecret sent =
        new KeepSecret(
            SessionToken.generate(new SecureRandom()),
            "warehouse.database.password",
            "sa/8Xk!connect".toCharArray());

    KeepSecret received = (KeepSecret) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.name(), received.name());
    assertArrayEquals(sent.secret(), received.secret());
  }

  /** A secret that came back changed would be a credential a ProtectedFeature cannot connect with. */
  @Test
  void carriesARevealedSecretByteForByte() {
    SecretRevealed sent = new SecretRevealed("sa/8Xk!connect \u00f1\u20ac".toCharArray());

    SecretRevealed received =
        (SecretRevealed) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertArrayEquals(sent.secret(), received.secret());
  }

  @Test
  void carriesAChangeOfOwnPasswordUnchanged() {
    ChangeOwnPassword sent =
        new ChangeOwnPassword(
            SessionToken.generate(new SecureRandom()),
            "Correct-Horse-1".toCharArray(),
            "Another-Horse-2".toCharArray());

    ChangeOwnPassword received =
        (ChangeOwnPassword) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertArrayEquals(sent.currentPassword(), received.currentPassword());
    assertArrayEquals(sent.newPassword(), received.newPassword());
  }

  @Test
  void carriesADeleteAccountUnchanged() {
    DeleteAccount sent =
        new DeleteAccount(SessionToken.generate(new SecureRandom()), "finch.mercer");

    DeleteAccount received = (DeleteAccount) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.accountName(), received.accountName());
  }

  @Test
  void carriesEveryAccountOfAListingUnchangedAndInOrder() {
    AccountsListed sent =
        new AccountsListed(
            List.of(
                new AccountSummary(
                    "finch.mercer",
                    Role.OPERATOR,
                    Optional.of(PasswordStrength.ACCEPTABLE),
                    Optional.of(Locale.forLanguageTag("es-ES")),
                    Optional.of(Duration.ofMinutes(10))),
                new AccountSummary(
                    "wren.holloway",
                    Role.ADMINISTRATOR,
                    Optional.of(PasswordStrength.STRONG),
                    Optional.empty(),
                    Optional.empty())));

    AccountsListed received =
        (AccountsListed) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertEquals(sent.accounts(), received.accounts());
  }

  /** An Account awaiting enrolment has no band, which the wire says rather than leaves out. */
  @Test
  void carriesAnAccountThatHasNoBandBecauseItHasNoPassword() {
    AccountsListed sent =
        new AccountsListed(
            List.of(
                new AccountSummary(
                    "juno.vale",
                    Role.OPERATOR,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));

    AccountsListed received =
        (AccountsListed) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertEquals(sent.accounts(), received.accounts());
    assertTrue(
        new String(MessageCodec.encode(sent), StandardCharsets.UTF_8)
            .contains("\"passwordStrength\":null"),
        "an absent band is written as something rather than as nothing");
  }

  @Test
  void carriesAListingOfNoAccountsAtAll() {
    AccountsListed sent = new AccountsListed(List.of());

    AccountsListed received =
        (AccountsListed) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertTrue(received.accounts().isEmpty(), "an empty listing is not a malformed message");
  }

  /**
   * Saying nothing about a language and naming one are different facts about a person, and a codec
   * that read a tag naming no language as "said nothing" would turn one into the other.
   */
  @Test
  void refusesAnAccountWhoseLanguagePreferenceNamesNoLanguage() {
    String message =
        """
        {"type":"AccountsListed","accounts":[{"name":"finch.mercer","role":"OPERATOR",\
        "passwordStrength":"WEAK","languagePreference":"???","lockedForMillis":null}]}\
        """;

    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(message.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void refusesAnAccountWithNoAnswerAboutItsBandAtAll() {
    String message =
        """
        {"type":"AccountsListed","accounts":[{"name":"finch.mercer","role":"OPERATOR",\
        "languagePreference":null,"lockedForMillis":null}]}\
        """;

    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(message.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void refusesAnAccountWithNoAnswerAboutItsLockoutAtAll() {
    String message =
        """
        {"type":"AccountsListed","accounts":[{"name":"finch.mercer","role":"OPERATOR",\
        "passwordStrength":"WEAK","languagePreference":null}]}\
        """;

    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(message.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void refusesAListingWhoseAccountsAreNotObjects() {
    String message = "{\"type\":\"AccountsListed\",\"accounts\":[\"finch.mercer\"]}";

    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(message.getBytes(StandardCharsets.UTF_8)));
  }

  /** Every one of the new codes is a constant a client has to be able to read back. */
  @Test
  void carriesEveryErrorCodeThisBuildKnows() {
    for (ErrorCode code : ErrorCode.values()) {
      ErrorResponse sent = new ErrorResponse(code);

      assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
    }
  }

  @Test
  void carriesAChangeOfTheInactivityPeriodUnchanged() {
    ChangeInactivityPeriod sent =
        new ChangeInactivityPeriod(
            SessionToken.generate(new SecureRandom()), InactivityPeriod.of(Duration.ofMinutes(45)));

    ChangeInactivityPeriod received =
        (ChangeInactivityPeriod) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.period(), received.period());
  }

  @Test
  void carriesExpirySwitchedOffAsSomethingRatherThanAsNothing() {
    ChangeInactivityPeriod sent =
        new ChangeInactivityPeriod(
            SessionToken.generate(new SecureRandom()), InactivityPeriod.disabled());

    ChangeInactivityPeriod received =
        (ChangeInactivityPeriod) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertEquals(InactivityPeriod.disabled(), received.period());
  }

  @Test
  void refusesAPeriodThatIsNotOne() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeRequest(
                bytes(
                    "{\"type\":\"ChangeInactivityPeriod\",\"token\":\"AAAAAAAAAAAAAAAAAAAAAA==\","
                        + "\"period\":\"whenever\"}")));
  }

  @Test
  void carriesHowLongASessionHasLeft() {
    SessionLive sent = new SessionLive(Optional.of(Duration.ofMinutes(15)));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /**
   * A kiosk Session has no countdown, and that is written as a field saying so rather than as a
   * field left out — the codec reads a missing field as a message it does not understand.
   */
  @Test
  void carriesASessionThatWillNeverExpire() {
    SessionLive sent = new SessionLive(Optional.empty());

    String json = text(MessageCodec.encode(sent));

    assertEquals("{\"type\":\"SessionLive\",\"expiresInMillis\":null}", json);
    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  @Test
  void refusesASessionLiveWithoutAnAnswerAboutWhenItExpires() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"SessionLive\"}")));
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes("{\"type\":\"SessionLive\",\"expiresInMillis\":\"soon\"}")));
  }

  @Test
  void carriesEveryReasonASessionEnded() {
    for (SessionEndedReason reason : SessionEndedReason.values()) {
      SessionEnded sent = new SessionEnded(reason);

      assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
    }
  }

  @Test
  void carriesAGrantedTokenByteForByte() {
    Granted sent = new Granted(SessionToken.generate(new SecureRandom()));

    Granted received = (Granted) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
  }

  /** The one thing an admission carries besides the token, and the reason it is not a refusal. */
  @Test
  void carriesTheResetAnOperatorIsOwedBeingToldAbout() {
    Granted sent =
        new Granted(
            SessionToken.generate(new SecureRandom()),
            Optional.of(Instant.parse("2026-03-01T09:00:00Z")));

    Granted received = (Granted) MessageCodec.decodeResponse(MessageCodec.encode(sent));

    assertEquals(sent.passwordResetAt(), received.passwordResetAt());
  }

  /** Nothing to tell is written as an explicit null, as everything optional here is. */
  @Test
  void carriesAnAdmissionWithNothingToTellAsSomethingRatherThanAsNothing() {
    Granted sent = new Granted(SessionToken.generate(new SecureRandom()));

    assertTrue(
        new String(MessageCodec.encode(sent), StandardCharsets.UTF_8)
            .contains("\"passwordResetAt\":null"));
    assertEquals(
        Optional.empty(),
        ((Granted) MessageCodec.decodeResponse(MessageCodec.encode(sent))).passwordResetAt());
  }

  @Test
  void refusesAnAdmissionWhoseResetIsNotAMoment() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes(
                    "{\"type\":\"Granted\",\"token\":\""
                        + Base64.getEncoder()
                            .encodeToString(SessionToken.generate(new SecureRandom()).copyOfBytes())
                        + "\",\"passwordResetAt\":\"last Tuesday\"}")));
  }

  @Test
  void carriesACreateAccountUnchanged() {
    CreateAccount sent =
        new CreateAccount(SessionToken.generate(new SecureRandom()), "finch.mercer", Role.OPERATOR);

    CreateAccount received = (CreateAccount) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.accountName(), received.accountName());
    assertEquals(Role.OPERATOR, received.role());
  }

  @Test
  void carriesAnInitiateResetUnchanged() {
    InitiateReset sent =
        new InitiateReset(SessionToken.generate(new SecureRandom()), "finch.mercer");

    InitiateReset received = (InitiateReset) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.accountName(), received.accountName());
  }

  @Test
  void carriesACompleteEnrolmentUnchanged() {
    CompleteEnrolment sent =
        new CompleteEnrolment(
            "finch.mercer", "K7QF-9M2X-3WBR".toCharArray(), "Another-Horse-2".toCharArray());

    CompleteEnrolment received =
        (CompleteEnrolment) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertEquals(sent.accountName(), received.accountName());
    assertArrayEquals(sent.secret(), received.secret());
    assertArrayEquals(sent.password(), received.password());
  }

  @Test
  void carriesAnEnrolmentIssuedUnchanged() {
    EnrolmentIssued sent =
        new EnrolmentIssued("K7QF-9M2X-3WBR-8ZDN-5YCG-VJH2-P4", Instant.parse("2026-03-04T09:00:00Z"));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /** A secret that runs out at no moment is a secret that never runs out, and there is no such one. */
  @Test
  void refusesAnEnrolmentIssuedThatExpiresAtNoMoment() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes("{\"type\":\"EnrolmentIssued\",\"secret\":\"K7QF\",\"expiresAt\":null}")));
  }

  @Test
  void carriesADeniedUnchanged() {
    Denied sent = Denied.because(DeniedReason.AUTH_FAILED);

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /** The one refusal that carries anything: a person is owed the wait as well as the word. */
  @Test
  void carriesALockoutWithWhatIsLeftOfIt() {
    Denied sent = Denied.lockedFor(Duration.ofMinutes(15));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /**
   * The reason and the time left are one fact, and a message that pairs them any other way is not a
   * message this build reads. Guessing — dropping the number, or inventing one — would be the
   * privileged process's answer being retold by whoever sent it.
   */
  @Test
  void refusesARefusalWhoseReasonAndWaitDoNotAgree() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes(
                    "{\"type\":\"Denied\",\"reason\":\"AUTH_FAILED\","
                        + "\"lockedForMillis\":900000}")));
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes(
                    "{\"type\":\"Denied\",\"reason\":\"LOCKED_OUT\","
                        + "\"lockedForMillis\":null}")));
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes("{\"type\":\"Denied\",\"reason\":\"LOCKED_OUT\"}")));
  }

  @Test
  void carriesAClearLockoutUnchanged() {
    ClearLockout sent = new ClearLockout(SessionToken.generate(new SecureRandom()), "finch.mercer");

    ClearLockout received = (ClearLockout) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.accountName(), received.accountName());
  }

  @Test
  void carriesAnExportAuthenticationEventsUnchanged() {
    ExportAuthenticationEvents sent =
        new ExportAuthenticationEvents(
            SessionToken.generate(new SecureRandom()), Path.of("/tmp/events.csv"));

    ExportAuthenticationEvents received =
        (ExportAuthenticationEvents) MessageCodec.decodeRequest(MessageCodec.encode(sent));

    assertArrayEquals(sent.token().copyOfBytes(), received.token().copyOfBytes());
    assertEquals(sent.destination(), received.destination());
  }

  @Test
  void carriesAnAuthenticationEventsExportedUnchanged() {
    AuthenticationEventsExported sent =
        new AuthenticationEventsExported(new AuthenticationEventExport(4312, false));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /** A count of entries is a count. A message saying an export held minus four is not one. */
  @Test
  void refusesAnExportThatHeldFewerThanNoEntries() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeResponse(
                bytes(
                    "{\"type\":\"AuthenticationEventsExported\",\"events\":-4,"
                        + "\"chainIntact\":true}")));
  }

  @Test
  void carriesAnOkUnchanged() {
    assertEquals(new Ok(), MessageCodec.decodeResponse(MessageCodec.encode(new Ok())));
  }

  @Test
  void carriesAnAssessedWithBothHalvesOfWhatThePolicySaid() {
    Assessed sent =
        new Assessed(
            new Assessment(
                List.of(PolicyViolation.ACCOUNT_NAME_BLOCKED, PolicyViolation.PASSWORD_TOO_SHORT),
                PasswordStrength.WEAK));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  @Test
  void carriesAnAcceptingAssessedWithNoViolationsAtAll() {
    Assessed sent = new Assessed(new Assessment(List.of(), PasswordStrength.STRONG));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  @Test
  void carriesEveryViolationOfAPolicyRefusalInOrder() {
    PolicyRefused sent =
        new PolicyRefused(
            List.of(
                PolicyViolation.ACCOUNT_NAME_BLANK,
                PolicyViolation.PASSWORD_WITHOUT_NUMBER,
                PolicyViolation.PASSWORD_BREACHED));

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  @Test
  void carriesAnErrorUnchanged() {
    ErrorResponse sent = new ErrorResponse(ErrorCode.STORE_UNAVAILABLE);

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
  }

  /** {@link ErrorResponse} promises it is {@code Error} on the wire, whatever Java calls it. */
  @Test
  void namesTheErrorResponseErrorOnTheWire() {
    String json = text(MessageCodec.encode(new ErrorResponse(ErrorCode.ADMINISTRATOR_EXISTS)));

    assertTrue(json.contains("\"type\":\"Error\""), () -> "wrong type field: " + json);
  }

  /** ADR-0003 chose JSON to be debuggable, so a reviewer must be able to read one in a trace. */
  @Test
  void writesTheTypeAndTheFieldsAsPlainJson() {
    String json =
        text(MessageCodec.encode(new Authenticate("wren", "Horse-1".toCharArray(), Role.OPERATOR)));

    assertEquals(
        "{\"type\":\"Authenticate\",\"accountName\":\"wren\",\"password\":\"Horse-1\","
            + "\"requestedRole\":\"OPERATOR\"}",
        json);
  }

  private static byte[] tokenOfRoundTripped(Request request) {
    Request received = MessageCodec.decodeRequest(MessageCodec.encode(request));
    return switch (received) {
      case ReportActivity report -> report.token().copyOfBytes();
      case AskIfSessionIsLive ask -> ask.token().copyOfBytes();
      case Logout logout -> logout.token().copyOfBytes();
      case AcknowledgePasswordReset seen -> seen.token().copyOfBytes();
      case ReadSecret read -> read.token().copyOfBytes();
      case ListAccounts list -> list.token().copyOfBytes();
      case ChangeOwnPassword change -> change.token().copyOfBytes();
      default -> throw new AssertionError("not a request about a Session: " + received);
    };
  }

  @Test
  void refusesAPayloadThatIsNotJson() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeRequest(bytes("not json at all")));
  }

  @Test
  void refusesAnEmptyPayload() {
    assertThrows(MalformedMessageException.class, () -> MessageCodec.decodeRequest(new byte[0]));
  }

  @Test
  void refusesJsonThatIsNotAnObject() {
    assertThrows(MalformedMessageException.class, () -> MessageCodec.decodeRequest(bytes("[1,2]")));
  }

  @Test
  void refusesARequestTypeThisBuildDoesNotAnswer() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeRequest(bytes("{\"type\":\"DeleteEveryAccount\"}")));
  }

  @Test
  void refusesAResponseTypeThisBuildDoesNotRead() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"Enrolled\"}")));
  }

  /** A request cannot arrive as a response, however well formed it is. */
  @Test
  void refusesARequestOfferedWhereAResponseBelongs() {
    byte[] request = MessageCodec.encode(new Assess("wren", "short".toCharArray()));

    assertThrows(MalformedMessageException.class, () -> MessageCodec.decodeResponse(request));
  }

  @Test
  void refusesAMessageWithAFieldMissing() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeRequest(bytes("{\"type\":\"Assess\",\"accountName\":\"wren\"}")));
  }

  @Test
  void refusesAFieldOfTheWrongShape() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeRequest(
                bytes("{\"type\":\"Assess\",\"accountName\":7,\"password\":\"Horse-1\"}")));
  }

  /**
   * A constant from a later protocol is refused rather than mapped onto a default. Guessing here
   * would have the privileged process act on a rule it does not have.
   */
  @Test
  void refusesAnEnumConstantThisBuildHasNeverHeardOf() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeRequest(
                bytes(
                    "{\"type\":\"Authenticate\",\"accountName\":\"wren\",\"password\":\"Horse-1\","
                        + "\"requestedRole\":\"AUDITOR\"}")));
  }

  @Test
  void refusesATokenThatIsNotTheLengthTheServiceIssues() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"Granted\",\"token\":\"AAAA\"}")));
  }

  @Test
  void refusesATokenThatIsNotBase64() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"Granted\",\"token\":\"not-!-b64\"}")));
  }

  @Test
  void refusesAPolicyRefusalCarryingNoReason() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"PolicyRefused\",\"violations\":[]}")));
  }

  /** Two values in one frame is a peer asking to be read two ways. It is read neither way. */
  @Test
  void refusesASecondValueBehindTheMessage() {
    assertThrows(
        MalformedMessageException.class,
        () -> MessageCodec.decodeResponse(bytes("{\"type\":\"Ok\"} {\"type\":\"Ok\"}")));
  }

  @Test
  void refusesAMessageThatRepeatsAField() {
    assertThrows(
        MalformedMessageException.class,
        () ->
            MessageCodec.decodeRequest(
                bytes(
                    "{\"type\":\"Assess\",\"accountName\":\"wren\",\"password\":\"a\","
                        + "\"password\":\"b\"}")));
  }

  /** The password crosses in the clear per ADR-0003; the objects around it still never print it. */
  @Test
  void printingARequestDoesNotPrintWhatIsInIt() {
    String printed =
        new Authenticate("wren.holloway", "Correct-Horse-1".toCharArray(), Role.OPERATOR)
            .toString();

    assertTrue(printed.contains("redacted"), () -> "not redacted: " + printed);
    assertFalse(printed.contains("Correct-Horse-1"), () -> "leaked the password: " + printed);
    assertFalse(printed.contains("wren.holloway"), () -> "leaked the Account name: " + printed);
  }

  /** An Account name is part of what the CredentialStore keeps secret, token or no token. */
  @Test
  void printingAClearLockoutPrintsNeitherTheTokenNorTheAccount() {
    SessionToken token = SessionToken.generate(new SecureRandom());

    String printed = new ClearLockout(token, "finch.mercer").toString();

    assertTrue(printed.contains("redacted"), () -> "not redacted: " + printed);
    assertFalse(printed.contains("finch.mercer"), () -> "leaked the Account name: " + printed);
    assertFalse(
        printed.contains(Base64.getEncoder().encodeToString(token.copyOfBytes())),
        () -> "leaked the token: " + printed);
  }

  /** The one message that carries a secret the service will never say again prints none of it. */
  @Test
  void printingAnEnrolmentPrintsNeitherTheSecretNorThePassword() {
    String offered =
        new CompleteEnrolment(
                "finch.mercer", "K7QF-9M2X".toCharArray(), "Another-Horse-2".toCharArray())
            .toString();
    String issued =
        new EnrolmentIssued("K7QF-9M2X", Instant.parse("2026-03-04T09:00:00Z")).toString();

    assertFalse(offered.contains("K7QF"), () -> "leaked the secret: " + offered);
    assertFalse(offered.contains("Another-Horse-2"), () -> "leaked the password: " + offered);
    assertFalse(offered.contains("finch.mercer"), () -> "leaked the Account name: " + offered);
    assertFalse(issued.contains("K7QF"), () -> "leaked the secret: " + issued);
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] payload) {
    return new String(payload, StandardCharsets.UTF_8);
  }
}
