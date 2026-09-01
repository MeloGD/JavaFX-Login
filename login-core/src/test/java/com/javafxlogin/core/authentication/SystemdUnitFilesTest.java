package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The unit files that are actually shipped, read as the artifacts they are.
 *
 * <p>Socket activation cannot be exercised by a suite — systemd is not in it — but four of the
 * ways it goes wrong are ways an <em>edit to these two files</em> goes wrong, and every one of
 * them fails silently rather than loudly. A second {@code ListenStream=} breaks
 * {@code System.inheritedChannel()} without a word; a missing {@code StandardOutput=} sends
 * whatever the JVM prints into the peer's connection; enabling the {@code .service} at boot
 * leaves the
 * privileged process running whether or not anybody logs in. The spike in
 * {@code docs/spikes/linux-service-activation.md} paid for that knowledge, and this is where it is
 * kept.
 */
class SystemdUnitFilesTest {

  private static final String UNIT_NAME = "javafx-login-authd";

  /** The group the installer creates, which exists to own this socket and nothing else. */
  private static final String DEDICATED_GROUP = "javafx-login";

  private final List<String> socketUnit = linesOf(UNIT_NAME + ".socket");
  private final List<String> serviceUnit = linesOf(UNIT_NAME + ".service");

  @Test
  void theSocketDeclaresExactlyOneListenStream() {
    // The load-bearing one. `StandardInput=socket` is only honoured for a socket unit that names a
    // single socket, and with a second one systemd quietly stops connecting the listening socket to
    // file descriptor 0 — where the JDK, and only there, looks for it.
    assertEquals(1, countOf(socketUnit, "ListenStream="));
  }

  @Test
  void theSocketIsServedByOneProcessRatherThanOnePerConnection() {
    assertTrue(declares(socketUnit, "Accept=no"));
  }

  @Test
  void theSocketsOwnershipAndModeAreDeclaredRatherThanLeftToUmask() {
    // systemd creates the socket, so it never exists with the wrong permissions — no restricted
    // parent directory and no chmod after bind(), which is the caveat ADR-0003 warns about.
    assertTrue(declares(socketUnit, "SocketUser=root"));
    assertTrue(declares(socketUnit, "SocketMode=0660"));
    assertEquals(
        DEDICATED_GROUP,
        valueOf(socketUnit, "SocketGroup="),
        "the socket's group must be the dedicated group the installer creates");
  }

  @Test
  void onlyTheSocketIsEnabledAtBoot() {
    assertTrue(declares(socketUnit, "WantedBy=sockets.target"));
    // A .service with an [Install] section is one somebody can enable, and an enabled one is a
    // privileged JVM running on a machine nobody has logged in to.
    assertFalse(
        serviceUnit.stream().anyMatch(line -> line.equals("[Install]")),
        "the service unit must not be installable: only the socket is enabled at boot");
  }

  @Test
  void theSocketNodeGoesWhenTheUnitDoes() {
    // systemd's default is to leave it. On this product that node is root-owned and names the
    // dedicated group, so an uninstall that left it behind would leave a path in /run naming a
    // gid the purge has just freed for reuse — and an uninstalled product that reads as
    // installed to anybody who looks there.
    assertTrue(
        declares(socketUnit, "RemoveOnStop=yes"),
        "the socket node must not outlive the unit: an uninstall would leave it in /run");
  }

  @Test
  void aServiceSystemdStoppedIsNotAServiceThatFailed() {
    // systemd stops this service on every upgrade and every removal, and a JVM asked to stop
    // ends at 143 — 128 + SIGTERM. Undeclared, systemd calls that a failure and leaves the unit
    // failed long after the installation that caused it succeeded: `systemctl --failed` then
    // names a machine that is installed and well, which is the one thing this product's Linux
    // side is careful never to look like.
    assertTrue(
        declares(serviceUnit, "SuccessExitStatus=143"),
        "a service systemd stopped must not be left failed: 143 is what SIGTERM ends a JVM at");
  }

  @Test
  void theServiceIsHandedTheListeningSocketOnFileDescriptorZero() {
    assertTrue(declares(serviceUnit, "StandardInput=socket"));
  }

  @Test
  void theServicesDiagnosticsGoToTheJournalAndNotIntoAClientConnection() {
    // Left at their default these inherit the socket, and anything the JVM prints — a stack
    // trace, a JVM warning — would be written into whatever peer happens to be connected.
    assertTrue(declares(serviceUnit, "StandardOutput=journal"));
    assertTrue(declares(serviceUnit, "StandardError=journal"));
  }

  @Test
  void theServiceRequiresTheSocketItIsActivatedBy() {
    assertTrue(declares(serviceUnit, "Requires=" + UNIT_NAME + ".socket"));
  }

  @Test
  void nothingReadsTheEnvironmentVariablesThatAreNotSetInThisMode() {
    // $LISTEN_FDS and $LISTEN_PID are deliberately unset under StandardInput=socket. Activation is
    // detected by asking for the inherited channel, and a unit file that pretended otherwise would
    // be describing a mechanism this service does not use.
    assertTrue(noSettingNames("LISTEN_FDS"));
    assertTrue(noSettingNames("LISTEN_PID"));
  }

  @Test
  void theJvmIsStartedWithoutOpeningUpTheJdk() {
    assertTrue(noSettingNames("--add-opens"));
    assertTrue(noSettingNames("--add-exports"));
    assertTrue(noSettingNames("sun.nio.ch"));
  }

  @Test
  void noPolkitRuleIsShipped() {
    // The spike proved a scoped polkit rule works and then rejected the design that needed one.
    // Socket activation grants the Operator no privilege at all, so a rule here would be a
    // privilege nobody asked for.
    try (Stream<Path> shipped = Files.walk(ShippedInstaller.directory())) {
      assertTrue(
          shipped.noneMatch(path -> path.getFileName().toString().endsWith(".rules")),
          "socket activation needs no polkit rule, and one must not be shipped");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Whether any <em>setting</em> in either unit names this. The comments are searched past on
   * purpose: both files explain at length why these are not read, and a test that failed over the
   * explanation would be one nobody could write the explanation under.
   */
  private boolean noSettingNames(String text) {
    return Stream.concat(socketUnit.stream(), serviceUnit.stream())
        .filter(SystemdUnitFilesTest::isASetting)
        .noneMatch(line -> line.contains(text));
  }

  private static boolean isASetting(String line) {
    return !line.startsWith("#") && line.contains("=");
  }

  private static boolean declares(List<String> unit, String setting) {
    return unit.stream().anyMatch(line -> line.equals(setting));
  }

  private static String valueOf(List<String> unit, String key) {
    return unit.stream()
        .filter(line -> line.startsWith(key))
        .map(line -> line.substring(key.length()))
        .findFirst()
        .orElse("");
  }

  private static long countOf(List<String> unit, String key) {
    return unit.stream().filter(line -> line.startsWith(key)).count();
  }

  private static List<String> linesOf(String unitFile) {
    try {
      return Files.readAllLines(ShippedInstaller.directory().resolve(unitFile)).stream()
          .map(String::strip)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
