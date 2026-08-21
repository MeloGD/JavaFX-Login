package com.javafxlogin.ui.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.ipc.ServiceUnreachableReason;
import com.javafxlogin.core.session.Session;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Seam 3: what opens when the AuthenticationService cannot be asked which window to open.
 *
 * <p>Story 90, and the answer is nothing. ADR-0002 makes the service the only party that can verify
 * a password, so a login screen in front of one that is not there is a gate that cannot gate
 * anything — and this application used to show that screen anyway, which was the degrading issue #16
 * asked for it to stop doing. What it shows instead names which of three things happened, because
 * that is the only part of it a person can act on.
 *
 * <p>The windows are put on this test's own stage, and the question that decides which one is asked
 * off the JavaFX application thread, so each case drives the flow itself rather than sharing a
 * {@code start}.
 */
class NoServiceAtStartupTest extends ApplicationTest {

  /**
   * The language every window in this test is drawn in, named rather than taken from the machine
   * the suite happens to be running on: what a screen says is asserted against the bundle it came
   * from, and a developer's locale is not a thing to assert against.
   */
  private static final InterfaceLanguage SPANISH =
      InterfaceLanguage.of(Locale.forLanguageTag("es"));

  private Stage stage;

  /** What is on the stage before this test opens anything, and what every wait below watches. */
  private Parent nothingYet;

  /**
   * The stage is emptied rather than merely taken, because TestFX hands every test in a class the
   * same primary stage: a window the last test left on it would answer a question about this one,
   * and the question here is precisely which window appeared.
   */
  @Override
  public void start(Stage stage) {
    this.stage = stage;
    this.nothingYet = new StackPane();
    stage.setScene(new Scene(nothingYet));
    stage.show();
  }

  @Test
  void refusesToStartAndSaysTheServiceIsNotRunning() {
    assertRefusesSaying(ServiceUnreachableReason.NOT_RUNNING, "service.not-running");
  }

  @Test
  void refusesToStartAndSaysTheTwoHalvesAreFromDifferentVersions() {
    assertRefusesSaying(
        ServiceUnreachableReason.INCOMPATIBLE_VERSION, "service.incompatible-version");
  }

  @Test
  void refusesToStartAndSaysThisAccountMayNotReachTheSocket() {
    assertRefusesSaying(
        ServiceUnreachableReason.SOCKET_NOT_ACCESSIBLE, "service.socket-not-accessible");
  }

  /**
   * The three sentences are different sentences. Asserting each against its own key would pass on a
   * build that had worded all three identically, which is the "something went wrong" this ticket
   * exists to refuse.
   */
  @Test
  void eachReasonSaysSomethingOfItsOwn() {
    String notRunning = SPANISH.say("service.not-running");
    String incompatible = SPANISH.say("service.incompatible-version");
    String inaccessible = SPANISH.say("service.socket-not-accessible");

    assertTrue(
        !notRunning.equals(incompatible)
            && !incompatible.equals(inaccessible)
            && !notRunning.equals(inaccessible),
        "the three remedies should not read the same");
  }

  /**
   * A service that answered the startup question and had gone by the next one. It refuses to start
   * for the same reason and with the same sentence: what the person is looking at either way is a
   * machine where nothing answered, and they are owed the remedy rather than a bare report that
   * something could not be reached.
   */
  @Test
  void aServiceThatGoesAwayBetweenTheTwoQuestionsIsAlsoARefusalToStart() {
    FakeLoginGate gate = new FakeLoginGate();
    gate.becomeUnreachable();

    open(gate);

    assertRefusal(SPANISH.say("service.not-running"));
  }

  /** The one control on the window ends the application, because there is nowhere else to go. */
  @Test
  void theOneControlThereIsClosesTheApplication() {
    AtomicBoolean closed = new AtomicBoolean();
    interact(
        () ->
            ServiceUnreachableWindow.show(
                stage, SPANISH, "service.not-running", () -> closed.set(true)));

    clickOn("#quit");

    assertTrue(closed.get(), "the button should have ended the application");
  }

  /** A service that is there opens a window, so that the refusal above is not simply what happens. */
  @Test
  void aReachableServiceStillOpensTheLoginScreen() {
    open(new FakeLoginGate().admitting("finch.mercer", "Another-Horse-2"));

    Parent shown = stage.getScene().getRoot();
    assertTrue(
        from(shown).lookup("#admit").tryQuery().isPresent(),
        "the login screen should be on the stage");
  }

  private void assertRefusesSaying(ServiceUnreachableReason reason, String key) {
    open(new FakeLoginGate().unreachableBecause(reason));

    assertRefusal(SPANISH.say(key));
  }

  private void assertRefusal(String remedy) {
    Parent shown = stage.getScene().getRoot();

    assertTrue(
        from(shown).lookup("#admit").tryQuery().isEmpty(),
        "the login screen should not have been opened");
    assertTrue(
        from(shown).lookup("#create").tryQuery().isEmpty(),
        "the wizard should not have been guessed");
    assertEquals(remedy, ((Label) from(shown).lookup("#remedy").query()).getText());
  }

  /**
   * Opens the flow and waits for whatever it decides to appear.
   *
   * <p>The questions behind that decision are asked off the JavaFX application thread, so the call
   * returns with nothing drawn and the window arrives later. That is the behaviour story 90 asks
   * for — a bounded wait must not be a frozen window — and it is why this waits for the stage to
   * stop holding what it was given rather than assuming anything about timing.
   */
  private void open(FakeLoginGate gate) {
    interact(() -> GateFlow.open(gate, stage, this::protectedFeature, SPANISH));
    try {
      WaitForAsyncUtils.waitFor(
          5, TimeUnit.SECONDS, () -> stage.getScene().getRoot() != nothingYet);
    } catch (TimeoutException e) {
      throw new AssertionError("no window was opened at all", e);
    }
    WaitForAsyncUtils.waitForFxEvents();
  }

  private Parent protectedFeature(Session session) {
    Label label = new Label("Has accedido a la funcionalidad detrás del sistema de login");
    label.setId("feature");
    return new StackPane(label);
  }
}
