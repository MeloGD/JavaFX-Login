package com.javafxlogin.core.ipc;

import com.javafxlogin.core.backup.Backup;
import java.util.Objects;

/**
 * The Backup was restored, and everything this deployment held before it is gone.
 *
 * <p>The same two numbers an export answered with, which is the point of them being the same record:
 * an Administrator who wrote down what the export came to can compare it against what the import
 * came to, and this system offers no other way of checking that a file made it across intact.
 *
 * <p>The Session that asked for this is over by the time this is read. That is not a failure and is
 * not said as one — the request said it would happen, and the client's part is to say what was
 * restored and send the person back to the login screen of the deployment they have just restored.
 *
 * @param backup how many Accounts and settings were restored
 */
public record BackupImported(Backup backup) implements Response {

  public BackupImported {
    Objects.requireNonNull(backup, "backup");
  }
}
