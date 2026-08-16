package com.javafxlogin.ui.login;

/**
 * The Account has the password its holder chose, and nobody else has ever known it.
 *
 * <p>It carries no Session. Completing an enrolment is not logging in — the person is handed back to
 * the login screen and types the password they have just chosen, which is also the first proof that
 * they can.
 */
public record Enrolled() implements EnrolmentOutcome {}
