package com.javafxlogin.ui.login;

/**
 * The service did what was asked, and there is nothing to carry back.
 *
 * <p>What it means depends on what was asked: an Account is gone, a Lockout is over, or the
 * deployment idles for a different length of time from now on. In every case the panel's next move
 * is the same — say so, and ask for the list again, because the list is what the screen is drawn
 * from.
 */
public record Administered() implements AdministrationOutcome {}
