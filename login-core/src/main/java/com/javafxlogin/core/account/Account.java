package com.javafxlogin.core.account;

import java.util.Objects;

/**
 * A named identity that can authenticate against this system. Every Account holds exactly one Role.
 *
 * <p>The name is not a label: because ADR-0002 keeps the account list unreadable to an unprivileged
 * attacker, a predictable name donates an entry of that list back for free.
 *
 * @param name         the name typed at the login prompt, matched exactly
 * @param role         the capability set attached to this Account
 * @param passwordHash the Argon2id hash as a PHC string, carrying its own salt and parameters
 */
public record Account(String name, Role role, String passwordHash) {

    public Account {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(passwordHash, "passwordHash");
    }

    /** Redacts the hash: an Account may be printed, its password material may not. */
    @Override
    public String toString() {
        return "Account[name=" + name + ", role=" + role + ", passwordHash=redacted]";
    }
}
