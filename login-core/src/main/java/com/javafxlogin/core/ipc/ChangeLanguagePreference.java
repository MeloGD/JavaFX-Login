package com.javafxlogin.core.ipc;

import com.javafxlogin.core.session.SessionToken;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Records the language an Account's holder reads the interface in, or that they have said nothing.
 * Answered with {@link Ok}, with an {@link ErrorResponse} where the Session is not an
 * Administrator's or where no Account holds that name, and with {@link SessionEnded} where the
 * Session is no longer live.
 *
 * <p>It carries a token rather than a password, as every administrative request does: the
 * Administrator proved who they were when the Session was granted, and the service checks the Role
 * of that Session rather than believing a client about which panel it thinks it is drawing.
 *
 * <p>Which languages exist is not part of this question. The service records the tag it is given
 * and never asks whether a bundle answers to it, because the bundles are in the client: a service
 * that held the list would have to be changed to add a language, which is the one thing issue #13
 * asks not to be true.
 *
 * @param token the Session asking, which must be an Administrator's
 * @param accountName whose LanguagePreference is recorded
 * @param preference the language, as a Locale, or empty where the Account is to say nothing and
 *     follow the machine it is read on
 */
public record ChangeLanguagePreference(
    SessionToken token, String accountName, Optional<Locale> preference) implements Request {

  public ChangeLanguagePreference {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(preference, "preference");
  }

  /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
  @Override
  public String toString() {
    return "ChangeLanguagePreference[redacted]";
  }
}
