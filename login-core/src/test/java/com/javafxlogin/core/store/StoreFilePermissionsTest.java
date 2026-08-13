package com.javafxlogin.core.store;

import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The store holds password hashes, so neither the account list nor the hashes may be readable by
 * the unprivileged account the graphical client runs as. That is the whole of ADR-0002.
 *
 * <p>The mode is asserted rather than the ownership: which user owns the file is a deployment
 * property the installer sets, while the mode is this code's responsibility.
 */
class StoreFilePermissionsTest {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> WORLD_READABLE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    @TempDir
    Path directory;

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theStoreFileIsCreatedOwnerOnly() throws IOException {
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }

        assertEquals(
                OWNER_ONLY,
                Files.getPosixFilePermissions(directory.resolve("credentials.db")),
                "the CredentialStore must not be readable by the account the client runs as");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void thePermissionsAreReassertedWhenAnExistingStoreIsReopened() throws IOException {
        Path storeFile = directory.resolve("credentials.db");
        try (ServiceHarness harness = ServiceHarness.cheap(directory)) {
            harness.send(new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));
        }
        Files.setPosixFilePermissions(storeFile, WORLD_READABLE);

        try (ServiceHarness reopened = ServiceHarness.cheap(directory)) {
            assertEquals(
                    OWNER_ONLY,
                    Files.getPosixFilePermissions(storeFile),
                    "an upgrade or a stray chmod must not leave the store readable");
        }
    }
}
