package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Copies the record of AuthenticationEvents to a file an Administrator names, so that it can be
 * read with their own tools. Answered with {@link AuthenticationEventsExported}, with an {@link
 * ErrorResponse} where the Session is not an Administrator's or the destination is refused, and
 * with {@link SessionEnded} where the Session is no longer live.
 *
 * <p>This is the only way the record leaves the service, and it deliberately leaves as a file
 * rather than as a response the application could put on a screen — story 74. What comes back says
 * how much was copied and whether the chain still held, and names not one event.
 *
 * <p>The destination is somewhere the service will write and nowhere else: an absolute path, in a
 * directory that already exists, outside the directory it keeps its own files in, and not something
 * that is already there. The copy is made owner-only, like everything else this process writes, so
 * reading it takes the privileges the service runs with. An export readable by whoever happens to
 * be logged in would be the in-app viewer, spelled differently.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param destination where to write the copy
 */
public record ExportAuthenticationEvents(SessionToken token, Path destination) implements Request {

  public ExportAuthenticationEvents {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(destination, "destination");
  }

  /** Redacts the token; where a person chose to put the copy is not a secret of this system. */
  @Override
  public String toString() {
    return "ExportAuthenticationEvents[token=redacted, destination=" + destination + "]";
  }
}
