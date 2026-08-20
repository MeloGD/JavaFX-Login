package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.session.Session;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: which language each window is drawn in, and when that changes.
 *
 * <p>Issue #13's ordering, asserted as a person meets it. Before anybody has authenticated the
 * screens follow the machine, because nothing else could answer for them, and the selector is what
 * somebody uses when the machine is wrong. After an admission the Account's own LanguagePreference
 * applies, because only then does this application know whose preference to apply — and the login
 * screen does not keep it afterwards, because the next person at this machine is not the last one.
 */
class LanguageWindowTest extends ApplicationTest {

  /** What this machine reads, as far as every test here is concerned. */
  private static final InterfaceLanguage MACHINE =
      InterfaceLanguage.of(Locale.forLanguageTag("es"));

  private static final Locale ENGLISH = Locale.forLanguageTag("en");
  private static final Locale SPANISH = Locale.forLanguageTag("es");

  private static final String OPERATOR = "finch.mercer";
  private static final String PASSWORD = "Another-Horse-2";

  private static final int PATIENCE_IN_SECONDS = 10;

  private FakeLoginGate gate;

  @Override
  public void start(Stage stage) {
    gate = new FakeLoginGate().admitting(OPERATOR, PASSWORD);
    GateFlow.open(gate, stage, this::protectedFeature, MACHINE);
  }

  private Parent protectedFeature(Session session) {
    Label label = new Label("the feature behind the gate");
    label.setId("feature");
    return new StackPane(label);
  }

  /** Criterion 2: the login screen is readable before anybody has authenticated. */
  @Test
  void theLoginScreenFollowsTheMachine() {
    assertEquals(MACHINE.say("login.admit"), textOf("#admit"));
    assertEquals(MACHINE.say("login.enrol-instead"), textOf("#enrolInstead"));
  }

  @Test
  void theSelectorOffersEveryLanguageThisBuildShips() {
    assertEquals(InterfaceLanguage.offered(), List.copyOf(selector().getItems()));
    assertEquals(SPANISH, selector().getValue(), "it starts on the one being read");
    assertEquals(
        InterfaceLanguage.offered().stream().map(InterfaceLanguage::nameOf).toList(),
        selector().getItems().stream().map(selector().getConverter()::toString).toList(),
        "each named in itself, so that somebody can find their own without reading the screen");
  }

  /** Criterion 3: the selector overrides the machine's locale for as long as this run lasts. */
  @Test
  void choosingALanguageRedrawsTheLoginScreenInIt() {
    choose(ENGLISH);

    InterfaceLanguage english = InterfaceLanguage.of(ENGLISH);
    await(() -> textOf("#admit").equals(english.say("login.admit")));
    assertEquals(ENGLISH, selector().getValue(), "the selector should be on the language chosen");
  }

  /**
   * What was on the screen is not carried across. It was decided in a language the person has just
   * said they do not read, and a window that changed language except for one sentence would be a
   * window still speaking the old one.
   */
  @Test
  void whatTheOldLanguageSaidDoesNotSurviveTheNewOne() {
    attempt(OPERATOR, "Wrong-Horse-9");
    await(() -> !message().isEmpty());

    choose(ENGLISH);

    InterfaceLanguage english = InterfaceLanguage.of(ENGLISH);
    await(() -> textOf("#admit").equals(english.say("login.admit")));
    assertEquals("", message(), "the refusal was said in the language they have just left");
  }

  /** Criterion 4: once somebody is admitted, their Account's own preference applies. */
  @Test
  void theAdmittedAccountsOwnLanguageIsWhatTheirWindowIsDrawnIn() {
    gate.readingTheInterfaceIn(ENGLISH);

    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertEquals(InterfaceLanguage.of(ENGLISH).say("session.log-out"), textOf("#logOut"));
  }

  /**
   * An Account that has said nothing keeps whatever was being read at the login screen — which is
   * the machine's, or what the selector was set to, and is the more useful of the two answers for
   * somebody who has just chosen a language and then logged in.
   */
  @Test
  void anAccountThatHasSaidNothingKeepsTheLanguageTheLoginScreenWasIn() {
    choose(ENGLISH);
    InterfaceLanguage english = InterfaceLanguage.of(ENGLISH);
    await(() -> textOf("#admit").equals(english.say("login.admit")));

    attempt(OPERATOR, PASSWORD);

    awaitTheProtectedFeature();
    assertEquals(english.say("session.log-out"), textOf("#logOut"));
  }

  /**
   * The login screen comes back in its own language, saying why in that language. A screen that had
   * learned the last Operator's language would be telling whoever walks up next what that person
   * reads.
   */
  @Test
  void theLoginScreenComesBackInItsOwnLanguageWhenASessionEnds() {
    gate.readingTheInterfaceIn(ENGLISH);
    attempt(OPERATOR, PASSWORD);
    awaitTheProtectedFeature();

    clickOn("#logOut");

    await(() -> lookup("#admit").tryQuery().isPresent());
    assertEquals(MACHINE.say("login.admit"), textOf("#admit"));
    await(() -> message().equals(MACHINE.say(SessionEndedText.LOGGED_OUT)));
  }

  /** The enrolment screen is on the same stage, before the same authentication: same language. */
  @Test
  void theEnrolmentScreenIsDrawnInTheLanguageTheLoginScreenWasIn() {
    choose(ENGLISH);
    InterfaceLanguage english = InterfaceLanguage.of(ENGLISH);
    await(() -> textOf("#admit").equals(english.say("login.admit")));

    clickOn("#enrolInstead");

    await(() -> lookup("#enrol").tryQuery().isPresent());
    assertEquals(english.say("enrolment.enrol"), textOf("#enrol"));
  }

  private void attempt(String accountName, String password) {
    clickOn("#accountName").write(accountName);
    clickOn("#password").write(password);
    clickOn("#admit");
  }

  private void choose(Locale language) {
    interact(() -> selector().setValue(language));
  }

  @SuppressWarnings("unchecked")
  private ComboBox<Locale> selector() {
    return (ComboBox<Locale>) lookup("#language").queryAs(ComboBox.class);
  }

  private void awaitTheProtectedFeature() {
    await(() -> lookup("#feature").tryQuery().isPresent());
  }

  private String textOf(String id) {
    return lookup(id).queryAs(Button.class).getText();
  }

  private String message() {
    Label label = lookup("#message").queryAs(Label.class);
    return label.getText() == null ? "" : label.getText();
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
