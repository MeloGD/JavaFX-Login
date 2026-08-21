package com.javafxlogin.ui.login;

import com.javafxlogin.core.backup.Backup;
import java.util.Objects;

/**
 * The Backup was written to the file the Administrator named, and this is what went into it.
 *
 * <p>Two numbers and no names, as the service answers it. They are worth putting in front of a
 * person because they are the only check this system offers that a file made it across intact: an
 * Administrator who writes down what an export came to can compare it against what the restore of
 * that file comes to, on the other machine, months later.
 *
 * @param backup how many Accounts and settings the file holds
 */
public record BackupWritten(Backup backup) implements BackupOutcome {

  public BackupWritten {
    Objects.requireNonNull(backup, "backup");
  }
}
