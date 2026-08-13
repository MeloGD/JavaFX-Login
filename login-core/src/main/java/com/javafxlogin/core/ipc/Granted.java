package com.javafxlogin.core.ipc;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.session.SessionToken;

import java.util.Objects;

/**
 * Authentication succeeded.
 *
 * @param token the opaque 128-bit SessionToken, never persisted and never logged
 * @param role  the capability set the Account holds
 */
public record Granted(SessionToken token, Role role) implements Response {

    public Granted {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(role, "role");
    }

    /** The Role may be printed; the token may not. */
    @Override
    public String toString() {
        return "Granted[role=" + role + ", token=redacted]";
    }
}
