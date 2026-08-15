package com.javafxlogin.ui.login;

/** Why the first-run wizard was not allowed to run. */
public enum FirstRunRefusedReason {

  /**
   * The single Administrator already exists. Somebody got there first, on this machine, and there
   * is no second one to create — the way in is the login screen.
   */
  ADMINISTRATOR_EXISTS,

  /**
   * The operating system does not treat the account this application is running under as an
   * administrator of the machine, or will not name it at all. Nothing about the Account or the
   * password was reached, and typing a different one changes nothing.
   */
  NOT_MACHINE_ADMINISTRATOR
}
