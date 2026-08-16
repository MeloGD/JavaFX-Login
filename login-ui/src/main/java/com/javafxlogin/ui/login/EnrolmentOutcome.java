package com.javafxlogin.ui.login;

/**
 * What came of somebody offering an enrolment secret and choosing a password.
 *
 * <p>Three outcomes, and the difference between the last two is what the person does next. A
 * refused password says which rules it broke and leaves the secret good, so they try another
 * password; a refused secret says nothing about why, and what it means is go back to whoever handed
 * it over.
 */
public sealed interface EnrolmentOutcome permits Enrolled, EnrolmentRefused, PolicyRefusal {}
