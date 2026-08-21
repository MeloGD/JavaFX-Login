package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Copies the Accounts and the configuration of this deployment into one file, sealed under a
 * password the Administrator types at the time. Answered with {@link BackupExported}, with an {@link
 * ErrorResponse} where the Session is not an Administrator's or the destination is refused, and with
 * {@link SessionEnded} where the Session is no longer live.
 *
 * <p>The password is not anybody's credential and is not checked against anything. It is what the
 * file is encrypted under, and it is the whole of what protects the file once it leaves this
 * machine — ADR-0006 chose that deliberately, because a backup bound to the machine that wrote it
 * is useless on the day that machine dies. It is typed rather than generated so that whoever will
 * need it in a year is the one who chose it.
 *
 * <p>What does <em>not</em> go into the file is the SecretVault, the MachineKey, the record of
 * AuthenticationEvents, and any Enrolment anybody is halfway through. See {@link
 * com.javafxlogin.core.backup.BackupContents}, which says why for each of them.
 *
 * <p>The destination is somewhere the service will write and nowhere else, by exactly the rule an
 * export of the record follows: an absolute path, in a directory that already exists, outside the
 * directory it keeps its own files in, and not something that is already there. The file is made
 * owner-only, which matters more here than anywhere — it holds every password hash in the
 * deployment.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param destination where to write the Backup
 * @param password what the Backup is sealed under
 */
public record ExportBackup(SessionToken token, Path destination, char[] password)
    implements Request {

  public ExportBackup {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(password, "password");
  }

  /**
   * Redacts the token and the password. Where a person chose to put the file is not a secret of this
   * system, and is the one part of this worth having in a message.
   */
  @Override
  public String toString() {
    return "ExportBackup[token=redacted, destination=" + destination + ", password=redacted]";
  }
}
