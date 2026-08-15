package com.javafxlogin.core.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
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

  @Test
  void carriesADeniedUnchanged() {
    Denied sent = new Denied(DeniedReason.AUTH_FAILED);

    assertEquals(sent, MessageCodec.decodeResponse(MessageCodec.encode(sent)));
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

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] payload) {
    return new String(payload, StandardCharsets.UTF_8);
  }
}
