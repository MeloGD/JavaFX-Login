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
import com.javafxlogin.core.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
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
