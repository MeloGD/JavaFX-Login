package com.javafxlogin.core.ipc;

import com.javafxlogin.core.backup.Backup;
import java.util.Objects;

/**
 * The Backup was written to the file the Administrator named.
 *
 * <p>It carries the backup package's own summary rather than a copy of its two numbers, the way
 * {@link Assessed} carries an {@code Assessment}: what a Backup came to is that package's word, and
 * repeating it here would be a second place for it to drift.
 *
 * <p>Not the Accounts. Not one name, and above all not one hash. What crosses this channel is how
 * much went into the file; the file itself is read by whoever holds the password, on whichever
 * machine they end up restoring it on.
 *
 * @param backup how many Accounts and settings were written
 */
public record BackupExported(Backup backup) implements Response {

  public BackupExported {
    Objects.requireNonNull(backup, "backup");
  }
}
