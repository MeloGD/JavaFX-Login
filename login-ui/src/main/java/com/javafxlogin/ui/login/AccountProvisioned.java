package com.javafxlogin.ui.login;

/**
 * What the AuthenticationService made of a request that ends with an EnrolmentSecret to hand over:
 * an Operator created, or an Operator's password taken away and replaced by one.
 *
 * <p>The two share a set because they answer the same way — with a secret shown once — and because
 * the panel does the same thing with either: show it, warn that it will not be shown again, and
 * never ask for it back.
 *
 * <p>A {@link PolicyRefusal} can only come of creating an Account, because that is the only one of
 * the two that names a new Account. A reset names one that already exists and passed the rules when
 * it was created, and the person at the panel cannot change its name — so the case is in the set
 * and never arrives from a reset, which is stated here rather than left for a reader to wonder at.
 *
 * <p>Not being able to ask at all is not an outcome and is not in this set — that is a {@link
 * ServiceUnreachableException}.
 */
public sealed interface AccountProvisioned
    permits EnrolmentSecretIssued, PolicyRefusal, AdministrationRefused {}
