package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request for one named secret.
 *
 * <p>The set is closed, like every other outcome a host product is handed, and it is short for a
 * reason: a ProtectedFeature asking for a credential has two things it can do — use what came back,
 * or do without and say why. There is no outcome here that says why the Vault would not open,
 * because the service does not say.
 *
 * <p>Keeping a secret answers with a set of its own, {@link SecretKeepingOutcome}, so that neither
 * caller has to handle a case the other's request produces. {@link SecretWithheld} is in both,
 * because it is the same refusal either way.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}, as it is everywhere else on the {@link LoginGate}.
 */
public sealed interface SecretOutcome permits SecretGiven, SecretWithheld {}
