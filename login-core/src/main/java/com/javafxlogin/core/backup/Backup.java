package com.javafxlogin.core.backup;

/**
 * What one Backup came to.
 *
 * <p>Two numbers and no names, the way an {@link com.javafxlogin.core.audit.AuthenticationEventExport}
 * is two numbers and no events: this is the whole of what the application learns about a file it
 * wrote or read. Which Accounts travelled is a question answered by logging in to the restored
 * deployment, not by a response crossing a socket.
 *
 * <p>The same record answers both directions, because they are the same fact seen twice — what an
 * export wrote is what the import of that file restores, and an import that came to a different pair
 * of numbers would be an import that dropped something.
 *
 * @param accounts how many Accounts the Backup holds. Never the ones awaiting enrolment: those stay
 *     on the machine that issued their secrets
 * @param settings how many configured settings it holds
 */
public record Backup(long accounts, long settings) {

  public Backup {
    if (accounts < 0 || settings < 0) {
      throw new IllegalArgumentException(
          "A Backup holds none of something or some of it, not " + accounts + " and " + settings);
    }
  }
}
