package com.javafxlogin.ui.login;

import com.javafxlogin.core.session.Session;
import java.util.Objects;

/**
 * Someone was admitted, and this is the Session it produced.
 *
 * @param session what the host product is handed, and what the SessionGuard watches
 */
public record Admitted(Session session) implements Admission {

  public Admitted {
    Objects.requireNonNull(session, "session");
  }
}
