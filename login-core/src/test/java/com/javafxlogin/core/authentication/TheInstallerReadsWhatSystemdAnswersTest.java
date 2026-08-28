package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code install.sh} makes of the words systemd answers it with.
 *
 * <p>Every other test of the installer reads it as text, which is most of what a suite can do with
 * a script that wires a machine. This one runs the part of it that asks systemd a question, against
 * a stub {@code systemctl} that answers the way the real one does — because that is where the
 * reading goes wrong. {@code systemctl is-enabled} answers a <em>word</em> and exits 0 for
 * {@code static} exactly as it does for {@code enabled}. A script that reads only the exit status
 * cannot tell "this unit has no [Install] section, as designed" from "somebody enabled the
 * privileged service", and refuses every installation over the first of them.
 *
 * <p>No machine is touched and no unit is read here: the whole subject is the reading.
 */
class TheInstallerReadsWhatSystemdAnswersTest {

  private static final String UNIT = "javafx-login-authd";

  @TempDir private Path directory;

  @Test
  void aServiceWithNoInstallSectionIsNotAServiceSomebodyEnabled() throws Exception {
    // `static` is what systemd calls a unit that cannot be enabled, and that is precisely what
    // javafx-login-authd.service is built to be. An installation must go through.
    WhatItDid ran = onAMachineSystemdIsRunningOn("static");

    assertEquals(
        0,
        ran.status(),
        "the installation was refused over a unit that is right:\n" + ran.output());
    assertTrue(
        ran.calls().contains("enable --now " + UNIT + ".socket"),
        "the socket was not enabled: " + ran.calls());
  }

  @Test
  void aServiceSomebodyEnabledStopsTheInstallationAndIsNamed() throws Exception {
    // The mistake this check exists for: an enabled .service is a privileged JVM running on a
    // machine nobody has logged in to.
    WhatItDid ran = onAMachineSystemdIsRunningOn("enabled");

    assertNotEquals(
        0, ran.status(), "an enabled privileged service was installed over:\n" + ran.output());
    assertTrue(
        ran.output().contains(UNIT + ".service"),
        "the refusal does not name the unit to disable: " + ran.output());
    // And it refuses before it wires anything. A postinst that enabled the socket and then
    // failed would leave dpkg half-configured on a machine that is now listening, which is the
    // state apt will not reinstall over.
    assertFalse(
        ran.calls().contains("enable --now"),
        "it enabled the socket before refusing, and left a listening half-installation: "
            + ran.calls());
  }

  @Test
  void aMachineWhoseSystemdHasNotBootedIsToldWhatWasNotDone() throws Exception {
    // A container image being built, or a chroot: systemctl is there and nothing was booted.
    // Enabling anything is impossible and failing would make the package unbuildable into an
    // image, so the one honest ending is to say what is left to do.
    WhatItDid ran = onAMachineWhoseSystemdHasNotBooted();

    assertEquals(
        0,
        ran.status(),
        "a machine that has not booted systemd cannot be an error:\n" + ran.output());
    assertTrue(ran.output().contains("nothing was enabled"), "it did not say so: " + ran.output());
    assertTrue(
        ran.output().contains("systemctl enable --now " + UNIT + ".socket"),
        "it did not name the command that finishes the job: " + ran.output());
    assertEquals("", ran.calls(), "it asked systemd something on a machine systemd is not on");
  }

  /**
   * Runs {@code enable_the_socket_only} where systemd is running and answers {@code state} to
   * {@code is-enabled}.
   */
  private WhatItDid onAMachineSystemdIsRunningOn(String state)
      throws IOException, InterruptedException {
    return whatTheInstallerDid(state, true);
  }

  /** Runs it where systemctl is installed and nothing has been booted: a chroot, or an image. */
  private WhatItDid onAMachineWhoseSystemdHasNotBooted() throws IOException, InterruptedException {
    return whatTheInstallerDid("static", false);
  }

  /**
   * Sources the shipped installer, tells it whether systemd is running, and calls the one function
   * that asks systemd anything — with a {@code systemctl} that answers {@code state} to
   * {@code is-enabled} and records every call it is given.
   */
  private WhatItDid whatTheInstallerDid(String state, boolean systemdIsRunning)
      throws IOException, InterruptedException {
    Path calls = Files.createFile(directory.resolve("calls"));
    Path stubs = Files.createDirectory(directory.resolve("stubs"));
    executable(
        stubs.resolve("systemctl"),
        """
        #!/bin/sh
        # The only systemctl call this exercises, with the real one's exit status: 0 for every
        # state a unit can be in except disabled and masked, and `static` is one of them.
        echo "$*" >> "${SYSTEMCTL_CALLS}"
        case "$1" in
          is-enabled)
            echo "${SYSTEMCTL_STATE}"
            case "${SYSTEMCTL_STATE}" in disabled|masked) exit 1 ;; esac
            ;;
        esac
        exit 0
        """);

    ProcessBuilder bash =
        new ProcessBuilder(
            "bash",
            "-c",
            """
            source "$1"
            booted="$2"
            systemd_is_running() { return "${booted}"; }
            enable_the_socket_only
            """,
            "install.sh",
            ShippedInstaller.directory().resolve("install.sh").toString(),
            systemdIsRunning ? "0" : "1");
    Map<String, String> environment = bash.environment();
    environment.put("PATH", stubs + ":" + environment.getOrDefault("PATH", "/usr/bin:/bin"));
    environment.put("SYSTEMCTL_STATE", state);
    environment.put("SYSTEMCTL_CALLS", calls.toString());

    Process ran = bash.redirectErrorStream(true).start();
    String output = new String(ran.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(ran.waitFor(30, TimeUnit.SECONDS), "install.sh did not finish");
    return new WhatItDid(ran.exitValue(), output, Files.readString(calls));
  }

  private static void executable(Path script, String body) throws IOException {
    Files.writeString(script, body);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  /** What one run of it ended as: how it exited, what it said, and what it asked systemd. */
  private record WhatItDid(int status, String output, String calls) {}
}
