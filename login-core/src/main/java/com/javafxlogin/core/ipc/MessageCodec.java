package com.javafxlogin.core.ipc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javafxlogin.core.account.AccountSummary;
import com.javafxlogin.core.account.PasswordStrength;
import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.audit.AuthenticationEventExport;
import com.javafxlogin.core.policy.Assessment;
import com.javafxlogin.core.policy.PolicyViolation;
import com.javafxlogin.core.session.InactivityPeriod;
import com.javafxlogin.core.session.SessionEndedReason;
import com.javafxlogin.core.session.SessionToken;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
 * LSASS. A secret out of the SecretVault crosses it the same way and for the same reason — the
 * channel is a socket inside a directory only two accounts on the machine can reach, and encrypting
 * a hop that the kernel already isolates would be answering a question nobody asked.
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
          case ChangeLanguagePreference change ->
              languagePreference(
                  carrying("ChangeLanguagePreference", change.token())
                      .put("accountName", change.accountName()),
                  change.preference());
          case ClearLockout clear ->
              carrying("ClearLockout", clear.token()).put("accountName", clear.accountName());
          case ExportAuthenticationEvents export ->
              carrying("ExportAuthenticationEvents", export.token())
                  .put("destination", export.destination().toString());
          case CreateAccount create ->
              carrying("CreateAccount", create.token())
                  .put("accountName", create.accountName())
                  .put("role", create.role().name());
          case InitiateReset reset ->
              carrying("InitiateReset", reset.token()).put("accountName", reset.accountName());
          case AcknowledgePasswordReset seen ->
              carrying("AcknowledgePasswordReset", seen.token());
          case CompleteEnrolment complete ->
              message("CompleteEnrolment")
                  .put("accountName", complete.accountName())
                  .put("secret", new String(complete.secret()))
                  .put("password", new String(complete.password()));
          case ChangeOwnPassword change ->
              carrying("ChangeOwnPassword", change.token())
                  .put("currentPassword", new String(change.currentPassword()))
                  .put("newPassword", new String(change.newPassword()));
          case DeleteAccount delete ->
              carrying("DeleteAccount", delete.token()).put("accountName", delete.accountName());
          case ListAccounts list -> carrying("ListAccounts", list.token());
          case ReadSecret read -> carrying("ReadSecret", read.token()).put("name", read.name());
          case KeepSecret keep ->
              carrying("KeepSecret", keep.token())
                  .put("name", keep.name())
                  .put("secret", new String(keep.secret()));
        };
    return write(message);
  }

  /** Encodes a response as the payload of one frame. */
  public static byte[] encode(Response response) {
    ObjectNode message =
        switch (response) {
          case Granted granted -> granted(granted);
          case EnrolmentIssued issued ->
              message("EnrolmentIssued")
                  .put("secret", issued.secret())
                  .put("expiresAt", issued.expiresAt().toString());
          case SecretRevealed revealed ->
              message("SecretRevealed").put("secret", new String(revealed.secret()));
          case Denied denied -> denied(denied);
          case AccountsListed listed -> accountsListed(listed);
          case Ok ignored -> message("Ok");
          case Assessed assessed -> assessed(assessed);
          case AuthenticationEventsExported exported -> exported(exported.export());
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
      case "ChangeLanguagePreference" ->
          new ChangeLanguagePreference(
              token(message), text(message, "accountName"), languagePreference(message));
      case "ClearLockout" -> new ClearLockout(token(message), text(message, "accountName"));
      case "ExportAuthenticationEvents" ->
          new ExportAuthenticationEvents(token(message), destination(message));
      case "CreateAccount" ->
          new CreateAccount(
              token(message), text(message, "accountName"), constant(Role.class, message, "role"));
      case "InitiateReset" -> new InitiateReset(token(message), text(message, "accountName"));
      case "AcknowledgePasswordReset" -> new AcknowledgePasswordReset(token(message));
      case "CompleteEnrolment" ->
          new CompleteEnrolment(
              text(message, "accountName"), chars(message, "secret"), chars(message, "password"));
      case "ChangeOwnPassword" ->
          new ChangeOwnPassword(
              token(message), chars(message, "currentPassword"), chars(message, "newPassword"));
      case "DeleteAccount" -> new DeleteAccount(token(message), text(message, "accountName"));
      case "ListAccounts" -> new ListAccounts(token(message));
      case "ReadSecret" -> new ReadSecret(token(message), text(message, "name"));
      case "KeepSecret" ->
          new KeepSecret(token(message), text(message, "name"), chars(message, "secret"));
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
      case "Granted" ->
          new Granted(
              token(message), moment(message, "passwordResetAt"), languagePreference(message));
      case "EnrolmentIssued" ->
          new EnrolmentIssued(
              text(message, "secret"),
              moment(message, "expiresAt")
                  .orElseThrow(
                      () ->
                          new MalformedMessageException(
                              "A secret that expires at no moment is not one this build reads")));
      case "SecretRevealed" -> new SecretRevealed(chars(message, "secret"));
      case "Denied" -> denied(message);
      case "AccountsListed" -> new AccountsListed(accountsIn(message));
      case "Ok" -> new Ok();
      case "Assessed" ->
          new Assessed(
              new Assessment(
                  violationsOf(message), constant(PasswordStrength.class, message, "strength")));
      case "AuthenticationEventsExported" ->
          new AuthenticationEventsExported(
              new AuthenticationEventExport(
                  count(message, "events"), flag(message, "chainIntact")));
      case "BootstrapNeeded" -> new BootstrapNeeded(flag(message, "needed"));
      case "PolicyRefused" -> policyRefused(message);
      case "SessionLive" -> new SessionLive(millis(message, "expiresInMillis"));
      case "SessionEnded" ->
          new SessionEnded(constant(SessionEndedReason.class, message, "reason"));
      case ERROR -> new ErrorResponse(constant(ErrorCode.class, message, "code"));
      default -> throw new MalformedMessageException("Not a response this build reads: " + type);
    };
  }

  /**
   * An admission, and the one thing it may carry besides the token: a reset the Operator is owed
   * being told about. Written as an explicit {@code null} where there is none, for the reason {@link
   * #sessionLive} gives — a missing field is a message this codec does not read.
   */
  private static ObjectNode granted(Granted granted) {
    return languagePreference(
        moment(carrying("Granted", granted.token()), "passwordResetAt", granted.passwordResetAt()),
        granted.languagePreference());
  }

  /**
   * A language, as a BCP 47 tag, or an explicit {@code null} where whoever it is about has said
   * nothing — written for the reason {@link #sessionLive} gives, and read back by {@link
   * #languagePreference(ObjectNode)}.
   */
  private static ObjectNode languagePreference(ObjectNode message, Optional<Locale> preference) {
    preference.ifPresentOrElse(
        present -> message.put("languagePreference", present.toLanguageTag()),
        () -> message.putNull("languagePreference"));
    return message;
  }

  private static ObjectNode moment(
      ObjectNode message, String field, Optional<Instant> moment) {
    moment.ifPresentOrElse(
        present -> message.put(field, present.toString()), () -> message.putNull(field));
    return message;
  }

  /**
   * A moment in time, as ISO-8601 with an offset, which is how story 76 has this system write every
   * one of them. A field that is present and says there is no such moment is read; one that is
   * missing, or that holds something that is not a moment, is not.
   */
  private static Optional<Instant> moment(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new MalformedMessageException(
          "The " + field + " field is missing or is neither a moment nor null");
    }
    if (value.isNull()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value.textValue()));
    } catch (DateTimeParseException e) {
      throw new MalformedMessageException("The " + field + " field is not a moment", e);
    }
  }

  /**
   * Every Account the administration panel lists, one object each.
   *
   * <p>Written out field by field like every other message here, rather than by handing the record
   * to Jackson: what leaves the privileged process is what this method names, so a later build that
   * added a field to {@link AccountSummary} would have to come here before it could put it on the
   * wire.
   */
  private static ObjectNode accountsListed(AccountsListed listed) {
    ArrayNode accounts = MAPPER.createArrayNode();
    for (AccountSummary account : listed.accounts()) {
      ObjectNode entry =
          MAPPER.createObjectNode().put("name", account.name()).put("role", account.role().name());
      account
          .passwordStrength()
          .ifPresentOrElse(
              band -> entry.put("passwordStrength", band.name()),
              () -> entry.putNull("passwordStrength"));
      languagePreference(entry, account.languagePreference());
      millis(entry, "lockedForMillis", account.lockedFor());
      accounts.add(entry);
    }
    ObjectNode message = message("AccountsListed");
    message.set("accounts", accounts);
    return message;
  }

  private static List<AccountSummary> accountsIn(ObjectNode message) {
    JsonNode field = message.get("accounts");
    if (!(field instanceof ArrayNode array)) {
      throw new MalformedMessageException("The accounts field is missing or is not a list");
    }
    List<AccountSummary> accounts = new ArrayList<>();
    for (JsonNode element : array) {
      if (!(element instanceof ObjectNode account)) {
        throw new MalformedMessageException("An Account is an object, and this is not one");
      }
      accounts.add(
          new AccountSummary(
              text(account, "name"),
              constant(Role.class, account, "role"),
              band(account),
              languagePreference(account),
              millis(account, "lockedForMillis")));
    }
    return accounts;
  }

  /**
   * The coarse band of an Account's password, or an explicit {@code null} where it has none yet —
   * an Account awaiting enrolment has no password for there to be a band of, and a message that
   * left the field out is one this codec does not read.
   */
  private static Optional<PasswordStrength> band(ObjectNode account) {
    JsonNode value = account.get("passwordStrength");
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new MalformedMessageException(
          "The passwordStrength field is missing or is neither a band nor null");
    }
    return value.isNull()
        ? Optional.empty()
        : Optional.of(constant(PasswordStrength.class, value));
  }

  /**
   * The language somebody reads, as a BCP 47 tag — or an explicit {@code null} where they have said
   * nothing, for the reason {@link #sessionLive} gives. Read the same way wherever it appears: on
   * an Account in the panel's list, on the admission that says whose preference to apply, and on
   * the request that records one.
   *
   * <p>A tag that names no language is refused rather than read as having said nothing: the two
   * mean different things to whoever reads them, and this codec does not turn one into the other.
   */
  private static Optional<Locale> languagePreference(ObjectNode message) {
    JsonNode value = message.get("languagePreference");
    if (value == null || !(value.isNull() || value.isTextual())) {
      throw new MalformedMessageException(
          "The languagePreference field is missing or is neither a language tag nor null");
    }
    if (value.isNull()) {
      return Optional.empty();
    }
    Locale preference = Locale.forLanguageTag(value.textValue());
    if (preference.getLanguage().isEmpty()) {
      throw new MalformedMessageException("No language is named by the tag " + value.textValue());
    }
    return Optional.of(preference);
  }

  private static ObjectNode exported(AuthenticationEventExport export) {
    return message("AuthenticationEventsExported")
        .put("events", export.events())
        .put("chainIntact", export.chainIntact());
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

  /**
   * A count of things, which is never negative and never a fraction. A message that says an export
   * held minus four entries is not one this build reads.
   */
  private static long count(ObjectNode message, String field) {
    JsonNode value = message.get(field);
    if (value == null || !value.isIntegralNumber() || value.longValue() < 0) {
      throw new MalformedMessageException(
          "The " + field + " field is missing or is not a count");
    }
    return value.longValue();
  }

  /** A path the peer chose, read as text. What the service will write to is the service's word. */
  private static Path destination(ObjectNode message) {
    try {
      return Path.of(text(message, "destination"));
    } catch (InvalidPathException e) {
      throw new MalformedMessageException("The destination is not a path on this machine", e);
    }
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
