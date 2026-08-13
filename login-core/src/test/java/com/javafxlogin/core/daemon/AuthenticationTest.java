package com.javafxlogin.core.daemon;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.DeniedReason;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Response;
import com.javafxlogin.core.session.SessionToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Seam 1: what Authenticate grants, what it denies, and how little the denial says. */
class AuthenticationTest {

    private static final String NAME = "wren.holloway";
    private static final String PASSWORD = "Correct-Horse-1";

    @TempDir
    Path directory;

    private ServiceHarness harness;

    @BeforeEach
    void openServiceWithAnAdministrator() {
        harness = ServiceHarness.cheap(directory);
        harness.send(new Bootstrap(NAME, PASSWORD.toCharArray()));
    }

    @AfterEach
    void closeService() {
        harness.close();
    }

    @Test
    void aCorrectPasswordIsGranted() {
        Response response = harness.send(new Authenticate(NAME, PASSWORD.toCharArray()));

        Granted granted = assertInstanceOf(Granted.class, response);
        assertEquals(Role.ADMINISTRATOR, granted.role());
    }

    @Test
    void theGrantedTokenIs128Bits() {
        Granted granted = (Granted) harness.send(new Authenticate(NAME, PASSWORD.toCharArray()));

        assertEquals(16, granted.token().copyOfBytes().length);
        assertEquals(16, SessionToken.LENGTH_IN_BYTES);
    }

    @Test
    void everyAuthenticationIssuesAFreshToken() {
        Granted first = (Granted) harness.send(new Authenticate(NAME, PASSWORD.toCharArray()));
        Granted second = (Granted) harness.send(new Authenticate(NAME, PASSWORD.toCharArray()));

        assertNotEquals(first.token(), second.token());
    }

    @Test
    void aWrongPasswordIsDenied() {
        Response response = harness.send(new Authenticate(NAME, "Wrong-Horse-9".toCharArray()));

        Denied denied = assertInstanceOf(Denied.class, response);
        assertEquals(DeniedReason.AUTH_FAILED, denied.reason());
    }

    @Test
    void anUnknownAccountIsDenied() {
        Response response = harness.send(new Authenticate("nobody.here", PASSWORD.toCharArray()));

        assertInstanceOf(Denied.class, response);
    }

    /**
     * The refusal must not distinguish "no such Account" from "wrong password", or the login screen
     * becomes an oracle for the account list — which ADR-0002 exists to keep secret.
     */
    @Test
    void theDenialRevealsNothingAboutWhetherTheAccountExists() {
        Response forWrongPassword = harness.send(new Authenticate(NAME, "Wrong-Horse-9".toCharArray()));
        Response forUnknownAccount = harness.send(new Authenticate("nobody.here", PASSWORD.toCharArray()));

        assertEquals(forWrongPassword, forUnknownAccount);
    }

    @Test
    void anAccountNameIsMatchedExactly() {
        Response response = harness.send(new Authenticate(NAME.toUpperCase(), PASSWORD.toCharArray()));

        assertInstanceOf(Denied.class, response);
    }
}
