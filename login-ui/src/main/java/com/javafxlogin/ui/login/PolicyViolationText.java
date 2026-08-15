package com.javafxlogin.ui.login;

import com.javafxlogin.core.policy.PolicyViolation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns the rules the AccountPolicy named into sentences a person reads.
 *
 * <p>The privileged process names rules and never words them, so that the wording — and its
 * translation — belongs to whoever draws the window. This is that side of the bargain, and it is
 * exhaustive on purpose: a rule added over there that nobody worded over here would reach a person
 * as a blank refusal, which is exactly the failure that cannot be told apart from a bug.
 *
 * <p>Every string here moves to a ResourceBundle when the interface learns a second language.
 */
final class PolicyViolationText {

  private PolicyViolationText() {}

  /** Every broken rule as one paragraph, in the order the policy reports them. */
  static String paragraphFor(List<PolicyViolation> violations) {
    return violations.stream()
        .map(PolicyViolationText::sentenceFor)
        .collect(Collectors.joining(" "));
  }

  static String sentenceFor(PolicyViolation violation) {
    return switch (violation) {
      case ACCOUNT_NAME_BLANK -> "Escribe un nombre de cuenta.";
      case ACCOUNT_NAME_BLOCKED ->
          "Ese nombre de cuenta es de los primeros que probaría quien atacase la instalación."
              + " Elige uno que no se pueda adivinar.";
      case PASSWORD_TOO_SHORT -> "La contraseña necesita al menos 12 caracteres.";
      case PASSWORD_TOO_LONG -> "La contraseña no puede pasar de 64 caracteres.";
      case PASSWORD_WITHOUT_UPPERCASE -> "La contraseña necesita alguna letra mayúscula.";
      case PASSWORD_WITHOUT_NUMBER -> "La contraseña necesita algún número.";
      case PASSWORD_WITHOUT_SPECIAL_CHARACTER ->
          "La contraseña necesita algún carácter especial.";
      case PASSWORD_BREACHED ->
          "Esa contraseña aparece en filtraciones conocidas, así que ya está en la lista por la"
              + " que empezaría cualquier ataque. Elige otra.";
    };
  }
}
