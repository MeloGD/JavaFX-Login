package com.javafxlogin.ui.login;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;

/**
 * One question put to the {@link LoginGate}, asked off the JavaFX application thread and answered
 * back on it.
 *
 * <p>The gate's windows ask exactly one question and wait for exactly one answer, and the two that
 * carry a password wait on an Argon2id hash to be computed at the other end. A window frozen for
 * that long looks broken, so no controller may ask on the thread that draws it — and having each
 * of them arrange that separately is how one of them ends up not doing it.
 *
 * <p>What is not an answer is worded here too, for the same reason: a service that cannot be
 * reached, and a defect below that produced no answer at all, mean the same thing to a person
 * whichever window they are looking at.
 */
final class GateAttempt {

  /** Every string here moves to a ResourceBundle when the interface learns a second language. */
  private static final String UNREACHABLE =
      "No se ha podido contactar con el servicio de autenticación.";

  private static final String UNANSWERED =
      "No se ha podido completar el intento. Vuelve a intentarlo.";

  private GateAttempt() {}

  /**
   * As {@link #make(String, Supplier, Consumer, Consumer)}, for a question carrying a password.
   *
   * @param password blanked once the attempt is over, however it ended
   */
  static <A> void make(
      String threadName,
      char[] password,
      Supplier<A> question,
      Consumer<A> answered,
      Consumer<String> unanswered) {
    make(
        threadName,
        () -> {
          try {
            return question.get();
          } finally {
            Arrays.fill(password, '\0');
          }
        },
        answered,
        unanswered);
  }

  /**
   * Asks {@code question} on a thread of its own and hands what comes back to {@code answered}, or
   * a sentence to {@code unanswered}, on the JavaFX application thread.
   *
   * @param threadName what the attempt is called in a stack trace
   */
  static <A> void make(
      String threadName, Supplier<A> question, Consumer<A> answered, Consumer<String> unanswered) {
    Thread.ofVirtual()
        .name(threadName)
        .start(
            () -> {
              try {
                A answer = question.get();
                Platform.runLater(() -> answered.accept(answer));
              } catch (ServiceUnreachableException e) {
                Platform.runLater(() -> unanswered.accept(UNREACHABLE));
              } catch (RuntimeException e) {
                // A defect somewhere below rather than an answer. The window still has to come
                // back: a person left facing dead controls cannot even try again.
                Platform.runLater(() -> unanswered.accept(UNANSWERED));
              }
            });
  }
}
