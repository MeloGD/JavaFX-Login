package com.javafxlogin.core.audit;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The AuthenticationEvents already written, which can be copied out and never read in.
 *
 * <p>It is a second interface rather than two more methods on {@link AuthenticationEventLog} so
 * that the write-only promise stays exactly as narrow as it was: whoever records an event holds a
 * reference that cannot read one back, and only the one request an Administrator has to be
 * authenticated to make holds this one.
 *
 * <p>Even here nothing returns an event. An export is a file the caller is told the size and the
 * soundness of — never a list the application could put on a screen, which is the leak story 74
 * refuses.
 */
public interface AuthenticationEventArchive {

  /**
   * Copies every entry still kept to one file, oldest first, and checks the chain as it goes.
   *
   * @param destination a file that does not exist yet, created owner-only
   * @throws java.nio.file.FileAlreadyExistsException if something is already at the destination.
   *     Named apart from the failures below because a caller can tell a person to choose another
   *     path, and because the refusal is the operating system's rather than a check made first and
   *     acted on afterwards
   * @throws IOException if the record cannot be read or the destination cannot be written — in
   *     which case nothing is left behind at the destination
   */
  AuthenticationEventExport exportTo(Path destination) throws IOException;
}
