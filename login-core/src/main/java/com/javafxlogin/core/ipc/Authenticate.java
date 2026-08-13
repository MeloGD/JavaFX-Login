package com.javafxlogin.core.ipc;

import java.util.Objects;

/**
 * Offers a name and a password. Answered with {@link Granted} or {@link Denied}, and with nothing
 * that distinguishes why a denial happened.
 *
 * @param accountName matched exactly against a stored Account
 * @param password    not retained by the service beyond verifying it
 */
public record Authenticate(String accountName, char[] password) implements Request {

    public Authenticate {
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(password, "password");
    }

    /** Redacted whole: the Account name is part of what the CredentialStore keeps secret. */
    @Override
    public String toString() {
        return "Authenticate[redacted]";
    }
}
