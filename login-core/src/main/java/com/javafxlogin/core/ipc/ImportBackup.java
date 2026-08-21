package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Replaces every Account and every setting in this deployment with the ones a Backup carries.
 * Answered with {@link BackupImported}, with an {@link ErrorResponse} where the Session is not an
 * Administrator's, where the file will not open, or where what it holds is not something this build
 * restores, and with {@link SessionEnded} where the Session is no longer live.
 *
 * <p><b>Wholesale, and never a merge.</b> ADR-0006 settled that: Accounts from two origins in one
 * store produce states nobody can reason about, and the person who would have to reason about them
 * is the one whose machine has just died. Everything that was here is gone when this returns
 * successfully, which is why the panel that sends it says so first and asks twice.
 *
 * <p>An import that is refused leaves the store exactly as it was. Nothing is written until the file
 * has opened, been read whole, and been found to be this build's — and then it is written in one
 * transaction, so the store is either the Backup's or untouched and never half of each.
 *
 * <p>It ends the Session that asked for it, and any other. The Administrator who sent this proved
 * they held an Account in a deployment that no longer exists; carrying that Session on into the
 * restored one would be the one moment in this system where a Session outlived the Account it names.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param source the Backup to restore, which the service reads and never writes
 * @param password the one the Backup was sealed under
 */
public record ImportBackup(SessionToken token, Path source, char[] password) implements Request {

  public ImportBackup {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(password, "password");
  }

  /**
   * Redacts the token and the password. Where a person keeps the file is not a secret of this
   * system, and is the one part of this worth having in a message.
   */
  @Override
  public String toString() {
    return "ImportBackup[token=redacted, source=" + source + ", password=redacted]";
  }
}
