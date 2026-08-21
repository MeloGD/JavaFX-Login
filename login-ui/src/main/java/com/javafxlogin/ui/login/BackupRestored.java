package com.javafxlogin.ui.login;

import com.javafxlogin.core.backup.Backup;
import java.util.Objects;

/**
 * The Backup was restored, and everything this deployment held before it is gone.
 *
 * <p>The same two numbers the export answered with, which is the point of them being the same
 * record: this is the far end of the check an Administrator started when they wrote the file down.
 *
 * <p>The Session that asked for this is over by the time it is read, because the deployment it named
 * is. That is not a failure and is not shown as one — it is what the request said it would do.
 *
 * @param backup how many Accounts and settings were restored
 */
public record BackupRestored(Backup backup) implements RestoreOutcome {

  public BackupRestored {
    Objects.requireNonNull(backup, "backup");
  }
}
