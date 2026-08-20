package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Someone was admitted, and this is the Session it produced.
 *
 * @param session what the host product is handed, and what the SessionGuard watches
 * @param passwordResetAt when an Administrator took this Account's password away, where the person
 *     has not been told about it yet. The service says it once, on the admission that proves the
 *     Account is theirs, so this window is the only chance anybody has to show it — see {@link
 *     SessionController}, which does.
 * @param languagePreference the language this Account's holder reads the interface in, or empty
 *     where they have said nothing. It arrives here and nowhere earlier because this is the moment
 *     the question can be answered: the login screen had a name somebody typed, and a name is not
 *     an Account until a password has proved it is theirs.
 */
public record Admitted(
    Session session, Optional<Instant> passwordResetAt, Optional<Locale> languagePreference)
    implements Admission {

  public Admitted {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(passwordResetAt, "passwordResetAt");
    Objects.requireNonNull(languagePreference, "languagePreference");
  }

  /** An ordinary admission, with nothing the person is owed being told. */
  public Admitted(Session session) {
    this(session, Optional.empty(), Optional.empty());
  }
}
