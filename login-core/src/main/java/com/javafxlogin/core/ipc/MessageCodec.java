package com.javafxlogin.core.ipc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Turns the messages into JSON, and back.
 *
 * <p>ADR-0003 chose the encoding and the cap; the catalogue is settled here, one message type at a
 * time, and each type names itself the way its own record says it should.
 *
 * <p>Every message is an object with a {@code type} field naming which one it is, and that name is
 * matched against a closed set. Nothing here is polymorphic deserialisation: the tree is read as
 * data and each type is built by hand, so no class named on the wire is ever instantiated. That is
 * the same reason ADR-0003 refused RMI — a privileged process must not let its peer choose what
 * gets constructed inside it.
 *
 * <p>Anything that is not a message this build knows — bad JSON, an unknown type, a missing field,
 * an enum constant from a later protocol — is a {@link MalformedMessageException} and never a
 * guess. Being lenient here would mean the privileged process acting on something it did not
 * understand.
 *
 * <p>Passwords become JSON strings on the way out, which is a copy this codec cannot avoid and
 * ADR-0003 already accepts: the password crosses the channel in the clear, as it does with PAM and
 * LSASS.
 */
public final class MessageCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          // A second value behind the message, or a repeated key, is a peer trying to be read two
          // ways at once. Neither is a message; both cost the connection.
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private static final String TYPE = "type";

  /** As {@link ErrorResponse} promises: {@code Error} here, the Java name only avoids shadowing. */
  private static final String ERROR = "Error";

  private MessageCodec() {}

  /** Encodes a request as the payload of one frame. */
  public static byte[] encode(Request request) {
    ObjectNode message =
        switch (request) {
          case Bootstrap bootstrap ->
              message("Bootstrap")
                  .put("administratorName", bootstrap.administratorName())
                  .put("password", new String(bootstrap.password()));
          case Authenticate authenticate ->
              message("Authenticate")
                  .put("accountName", authenticate.accountName())
                  .put("password", new String(authenticate.password()))
                  .put("requestedRole", authenticate.requestedRole().name());
          case Assess assess ->
              message("Assess")
                  .put("accountName", assess.accountName())
                  .put("password", new String(assess.password()));
          case AskIfBootstrapNeeded ignored -> message("AskIfBootstrapNeeded");
          case ReportActivity report -> carrying("ReportActivity", report.token());
          case AskIfSessionIsLive ask -> carrying("AskIfSessionIsLive", ask.token());
          case Logout logout -> carrying("Logout", logout.token());
          case ChangeInactivityPeriod change ->
              carrying("ChangeInactivityPeriod", change.token())
                  .put("period", change.period().text());
          case ClearLockout clear ->
              carrying("ClearLockout", clear.token()).put("accountName", clear.accountName());
        };
    return write(message);
  }

  /** Encodes a response as the payload of one frame. */
  public static byte[] encode(Response response) {
    ObjectNode message =
        switch (response) {
          case Granted granted -> carrying("Granted", granted.token());
          case Denied denied -> denied(denied);
          case Ok ignored -> message("Ok");
          case Assessed assessed -> assessed(assessed);
          case BootstrapNeeded needed -> message("BootstrapNeeded").put("needed", needed.needed());
          case PolicyRefused refused -> carrying("PolicyRefused", refused.violations());
          case SessionLive live -> sessionLive(live);
          case SessionEnded ended -> message("SessionEnded").put("reason", ended.reason().name());
          case ErrorResponse error -> message(ERROR).put("code", error.code().name());
        };
    return write(message);
  }

  /**
   * Reads what a client sent.
   *
   * @throws MalformedMessageException if it is not a request this build knows
   */
  public static Request decodeRequest(byte[] payload) {
    ObjectNode message = read(payload);
    String type = text(message, TYPE);
    return switch (type) {
      case "Bootstrap" ->
          new Bootstrap(text(message, "administratorName"), chars(message, "password"));
      case "Authenticate" ->
          new Authenticate(
              text(message, "accountName"),
              chars(message, "password"),
              constant(Role.class, message, "requestedRole"));
      case "Assess" -> new Assess(text(message, "accountName"), chars(message, "password"));
      case "AskIfBootstrapNeeded" -> new AskIfBootstrapNeeded();
      case "ReportActivity" -> new ReportActivity(token(message));
      case "AskIfSessionIsLive" -> new AskIfSessionIsLive(token(message));
      case "Logout" -> new Logout(token(message));
      case "ChangeInactivityPeriod" -> new ChangeInactivityPeriod(token(message), period(message));
      case "ClearLockout" -> new ClearLockout(token(message), text(message, "accountName"));
      default -> throw new MalformedMessageException("Not a request this build answers: " + type);
    };
  }

  /**
   * Reads what the service answered.
   *
   * @throws MalformedMessageException if it is not a response this build knows
   */
  public static Response decodeResponse(byte[] payload) {
    ObjectNode message = read(payload);
    String type = text(message, TYPE);
    return switch (type) {
      case "Granted" -> new Granted(token(message));
      case "Denied" -> denied(message);
      case "Ok" -> new Ok();
      case "Assessed" ->
          new Assessed(
              new Assessment(
                  violationsOf(message), constant(PasswordStrength.class, message, "strength")));
      case "BootstrapNeeded" -> new BootstrapNeeded(flag(message, "needed"));
      case "PolicyRefused" -> policyRefused(message);
      case "SessionLive" -> new SessionLive(millis(message, "expiresInMillis"));
      case "SessionEnded" ->
          new SessionEnded(constant(SessionEndedReason.class, message, "reason"));
      case ERROR -> new ErrorResponse(constant(ErrorCode.class, message, "code"));
      default -> throw new MalformedMessageException("Not a response this build reads: " + type);
    };
  }

  private static ObjectNode assessed(Assessed assessed) {
    Assessment assessment = assessed.assessment();
    return carrying("Assessed", assessment.violations())
        .put("strength", assessment.strength().name());
  }

  private static ObjectNode carrying(String type, List<PolicyViolation> violations) {
    ObjectNode message = message(type);
    message.set("violations", violations(violations));
    return message;
  }

  /** Base64 rather than an array of numbers: a token is opaque, and stays one line of a frame. */
  private static ObjectNode carrying(String type, SessionToken token) {
    return message(type).put("token", Base64.getEncoder().encodeToString(token.copyOfBytes()));
  }

  /**
   * Expiry switched off is written as an explicit {@code null} rather than by leaving the field
   * out. A missing field is a message this codec does not read; a field that is present and says
   * "there is no expiry" is one it does.
   */
  private static ObjectNode sessionLive(SessionLive live) {
    return millis(message("SessionLive"), "expiresInMillis", live.expiresIn());
  }

  /** As {@link #sessionLive}: a refusal that is no Lockout says so rather than staying silent. */
  private static ObjectNode denied(Denied denied) {
    return millis(
        message("Denied").put("reason", denied.reason().name()),
        "lockedForMillis",
        denied.lockedFor());
  }

  /**
   * Reads a refusal, and refuses one that says how long it lasts without being a Lockout — or that
   * is a Lockout and does not. The pairing is the record's own rule, and a message that breaks it
   * is not one this build reads.
   */
  private static Response denied(ObjectNode message) {
    DeniedReason reason = constant(DeniedReason.class, message, "reason");
    Optional<Duration> lockedFor = millis(message, "lockedForMillis");
    try {
      return new Denied(reason, lockedFor);
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException("Not a refusal this build reads: " + reason, e);
    }
  }

  private static ObjectNode millis(ObjectNode message, String field, Optional<Duration> duration) {
    duration.ifPresentOrElse(
        present -> message.put(field, present.toMillis()), () -> message.putNull(field));
    return message;
  }

  private static Optional<Duration> millis(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !(value.isNull() || value.isIntegralNumber())) {
      throw new MalformedMessageException(
          "The " + field + " field is missing or is neither a whole number nor null");
    }
    return value.isNull() ? Optional.empty() : Optional.of(Duration.ofMillis(value.longValue()));
  }

  private static InactivityPeriod period(ObjectNode message) {
    try {
      return InactivityPeriod.parse(text(message, "period"));
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException("Not an inactivity period this build reads", e);
    }
  }

  private static Response policyRefused(ObjectNode message) {
    List<PolicyViolation> violations = violationsOf(message);
    if (violations.isEmpty()) {
      throw new MalformedMessageException("A PolicyRefused carrying no reason is not one");
    }
    return new PolicyRefused(violations);
  }

  private static ObjectNode message(String type) {
    return MAPPER.createObjectNode().put(TYPE, type);
  }

  private static ArrayNode violations(List<PolicyViolation> violations) {
    ArrayNode array = MAPPER.createArrayNode();
    violations.forEach(violation -> array.add(violation.name()));
    return array;
  }

  private static List<PolicyViolation> violationsOf(ObjectNode message) {
    JsonNode field = message.get("violations");
    if (!(field instanceof ArrayNode array)) {
      throw new MalformedMessageException("The violations field is missing or is not a list");
    }
    List<PolicyViolation> violations = new ArrayList<>();
    for (JsonNode element : array) {
      violations.add(constant(PolicyViolation.class, element));
    }
    return violations;
  }

  private static SessionToken token(ObjectNode message) {
    try {
      return SessionToken.of(Base64.getDecoder().decode(text(message, "token")));
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException("The token is not one this service could have issued", e);
    }
  }

  private static <E extends Enum<E>> E constant(Class<E> type, ObjectNode message, String field) {
    return constant(type, message.get(field));
  }

  /** An unknown constant is refused rather than mapped to a default, which would be a guess. */
  private static <E extends Enum<E>> E constant(Class<E> type, JsonNode field) {
    if (field == null || !field.isTextual()) {
      throw new MalformedMessageException(
          "Expected the name of a " + type.getSimpleName() + ", and found " + field);
    }
    try {
      return Enum.valueOf(type, field.textValue());
    } catch (IllegalArgumentException e) {
      throw new MalformedMessageException(
          "No " + type.getSimpleName() + " named " + field.textValue() + " in this build", e);
    }
  }

  private static String text(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !value.isTextual()) {
      throw new MalformedMessageException("The " + field + " field is missing or is not text");
    }
    return value.textValue();
  }

  /** Strictly boolean: a {@code "true"} or a {@code 1} is not a message this codec reads. */
  private static boolean flag(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !value.isBoolean()) {
      throw new MalformedMessageException("The " + field + " field is missing or is not a flag");
    }
    return value.booleanValue();
  }

  private static char[] chars(ObjectNode message, String field) {
    return text(message, field).toCharArray();
  }

  private static ObjectNode read(byte[] payload) {
    try {
      JsonNode message = MAPPER.readTree(payload);
      if (!(message instanceof ObjectNode object)) {
        throw new MalformedMessageException("A message is a JSON object, and this is not one");
      }
      return object;
    } catch (IOException e) {
      throw new MalformedMessageException("The payload of the frame is not JSON", e);
    }
  }

  private static byte[] write(ObjectNode message) {
    try {
      return MAPPER.writeValueAsBytes(message);
    } catch (JsonProcessingException e) {
      // The tree was built here, out of strings and names. Nothing in it can fail to serialise.
      throw new IllegalStateException("Could not write a message this codec built", e);
    }
  }
}
