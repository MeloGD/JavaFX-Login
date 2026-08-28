package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The package that puts this product on an Ubuntu machine, read as the artifacts it is made of.
 *
 * <p>None of what these files do can be exercised by a suite: dpkg is not in it, and what is being
 * checked is what a machine is left like rather than what any code computes. What <em>can</em> be
 * kept here is the set of mistakes that leave a working installation behind them and are found
 * later by somebody who has lost something — an upgrade that loosens the mode on the directory
 * holding every password hash, a removal that takes the Accounts with it, a maintainer script that
 * names a path jpackage does not put anything at. Each of those installs cleanly and says nothing.
 *
 * <p>{@code docs/manual-checks/linux-packaging.md} covers the rest, on a machine.
 */
class DebianPackageTest {

  /** Where the package puts the product. What jpackage stages goes under its {@code lib}. */
  private static final String PAYLOAD = "/opt/javafx-login";

  private static final String STATE_DIRECTORY = "/var/lib/javafx-login";

  private final String buildScript = read("build-deb.sh");
  private final String postinst = read("debian/postinst");
  private final String prerm = read("debian/prerm");
  private final String postrm = read("debian/postrm");
  private final String copyright = read("debian/copyright");
  private final String notices = read("THIRD-PARTY-NOTICES.md");
  private final List<String> serviceUnit = lines("javafx-login-authd.service");

  @Test
  void thePackageWiresTheMachineWithTheSameScriptADeveloperRunsByHand() {
    // Two implementations of "what a machine needs" would be two places for it to be wrong, and
    // the one being debugged would be whichever the person happened to be reading.
    assertEquals(
        PAYLOAD, settingIn(postinst, "PAYLOAD"), "the postinst reaches into another payload");
    assertTrue(
        postinst.contains("\"${PAYLOAD}/lib/systemd/install.sh\""),
        "the postinst does not run the installer the package ships");
    assertTrue(
        buildScript.contains("${WORK}/stage/systemd,${WORK}/stage/doc"),
        "build-deb.sh does not stage the directories the maintainer scripts reach into");
    assertTrue(
        buildScript.contains("\"${HERE}/install.sh\" \"${WORK}/stage/systemd/install.sh\""),
        "install.sh is not the script the package stages");
  }

  @Test
  void theServiceIsStartedByAPathThePackageActuallyPutsSomethingAt() {
    // The unit is registered by the postinst and read by systemd at the first connection, so an
    // ExecStart= that names nothing fails days later, at somebody's login screen.
    assertTrue(
        valueOf(serviceUnit, "ExecStart=").startsWith(PAYLOAD + "/bin/javafx-login-authd "),
        "the .service starts something other than the launcher jpackage builds");
    assertTrue(
        buildScript.contains("readonly SERVICE_LAUNCHER='javafx-login-authd'"),
        "build-deb.sh builds a launcher under another name");
  }

  @Test
  void bothUnitsPointAtDocumentationThePackageCarries() {
    for (String unit : List.of("javafx-login-authd.service", "javafx-login-authd.socket")) {
      String named = valueOf(lines(unit), "Documentation=file:");
      assertTrue(
          named.startsWith(PAYLOAD + "/lib/doc/"),
          () -> unit + " names documentation outside the payload: " + named);
      assertTrue(
          Files.isRegularFile(ShippedInstaller.manualChecks().resolve(fileNameOf(named))),
          () -> unit + " names " + named + ", which is not a document this repository has");
    }
  }

  @Test
  void everyUpgradeAssertsThePermissionsOnTheDirectoryItCannotSeeInside() {
    // The one that goes wrong silently. A store left group-readable by an upgrade keeps working
    // perfectly, and the only property this product really has is gone.
    assertTrue(
        postinst.contains("\"${PAYLOAD}/lib/systemd/install.sh\""),
        "nothing in the postinst reasserts the deployment's permissions");
    assertTrue(
        read("install.sh").contains("install -d -o root -g root -m 0700 \"${STATE_DIRECTORY}\""),
        "install.sh does not reassert the state directory's owner and mode");
  }

  @Test
  void migrationsRunWhileSomebodyIsStillWatchingTheInstallation() {
    assertTrue(
        postinst.contains("--upgrade"),
        "the postinst leaves migrations to the next activation, where nobody sees them fail");
  }

  @Test
  void aRefusedUpgradeLeavesNothingForAnybodyToConnectTo() {
    // The order is the whole of the refusal's worth. Migrating after the socket is enabled leaves
    // a machine whose upgrade was refused listening anyway: the next login activates a service
    // that dies on the store it cannot read, and reports it as a service that is not running.
    int migrated = postinst.indexOf("\"${STORE}\" --upgrade");
    int wired = postinst.indexOf("\"${PAYLOAD}/lib/systemd/install.sh\" ${admitted}");

    assertTrue(migrated >= 0 && wired >= 0, "the postinst no longer does both of those things");
    assertTrue(
        migrated < wired,
        "the postinst enables the socket before it finds out whether the files can be opened");
  }

  @Test
  void theSocketStopsForAnUpgradeAndNotOnlyForARemoval() {
    // dpkg unpacks the new payload immediately after the prerm. A socket still listening through
    // that lets any connection activate a privileged JVM on a half-replaced /opt/javafx-login.
    int stopped = prerm.indexOf("systemctl stop \"${UNIT}.socket\"");
    int onlyOnARemoval = prerm.indexOf("if [ \"$1\" = remove ]");

    assertTrue(stopped >= 0, "the prerm never stops the socket");
    assertTrue(
        stopped < onlyOnARemoval,
        "the socket is stopped only on a removal, and stays listening through an upgrade");
  }

  @Test
  void theGroupTheSocketIsReadableByIsTheGroupTheInstallerCreates() {
    // Two files name it and nothing else does: the unit that hands the socket to a group, and the
    // script that creates that group. A drift here is a socket nobody on the machine can open,
    // and the client reports it as an account that may not reach the service.
    String declared = valueOf(lines("javafx-login-authd.socket"), "SocketGroup=");
    assertEquals(
        "'" + declared + "'",
        settingIn(read("install.sh"), "readonly DEDICATED_GROUP"),
        "the socket's group is not the group the installer creates");
  }

  @Test
  void nothingIsRunningOnTopOfAPayloadThatIsAboutToBeReplaced() {
    assertTrue(
        prerm.contains("systemctl stop \"${UNIT}.service\""),
        "the prerm lets the privileged process run on through the upgrade");
  }

  @Test
  void anUninstallKeepsTheDeploymentAndOnlyAPurgeDestroysIt() {
    assertTrue(
        removalCase().contains("has been kept"),
        "the postrm does not say that the deployment survived the removal");
    assertFalse(
        removalCase().contains("rm -rf"),
        "removing this package destroys a deployment, which a reinstall must not have to survive");
    assertTrue(
        purgeCase().contains("rm -rf \"${STATE_DIRECTORY}\""),
        "a purge does not destroy the deployment it says it destroys");
    assertTrue(
        postrm.contains("STATE_DIRECTORY=" + STATE_DIRECTORY),
        "the postrm purges some directory other than the deployment's");
  }

  @Test
  void thePurgeSaysWhatItIsDestroyingWhileItIsStillThere() {
    // The message is the last record of what was lost, so it names the things rather than the
    // directory: "every Account and its password" is what somebody has to have read.
    String purge = purgeCase();
    assertTrue(purge.indexOf("destroying") < purge.indexOf("rm -rf"), "it destroys before it says");
    for (String named : List.of("Account", "SecretVault", "authentication", "Backup")) {
      assertTrue(purge.contains(named), () -> "the purge does not say it destroys the " + named);
    }
  }

  @Test
  void theServiceIsNotSomethingSystemdStartsAtBootAndNotSomethingAPersonCanClick() {
    // jpackage will happily install a launcher as a service of its own — enabled at boot, which is
    // the privileged JVM ADR-0002 exists to avoid — and will put every launcher in the
    // applications menu, which offers a person a process that cannot start without systemd.
    assertFalse(
        buildScript.contains("launcher-as-service"),
        "the package installs the service the way jpackage does, which enables it at boot");
    assertTrue(
        buildScript.contains("linux-shortcut=false"),
        "the privileged service is offered in the applications menu");
  }

  @Test
  void theAttributionTheGplRequiresIsInThePackageRatherThanInTheRepository() {
    // A licence obligation, and the one part of packaging that is not about this machine at all:
    // the runtime and the toolkit inside the .deb are GPL, and handing somebody the package
    // without this notice is not permitted.
    for (String required : List.of("OpenJFX", "Classpath Exception", "OpenJDK")) {
      assertTrue(notices.contains(required), () -> "the notices do not name " + required);
    }
    assertTrue(
        copyright.contains("GPL-2.0-with-classpath-exception"),
        "the package's copyright file does not carry the licence the runtime is under");
    assertTrue(
        buildScript.contains("THIRD-PARTY-NOTICES.md"),
        "the notices are in the repository and not in the package");
    assertTrue(
        buildScript.contains("--resource-dir \"${HERE}/debian\""),
        "the copyright file the repository holds is not the one the package ships");
  }

  /** The value of a {@code NAME=value} line in a maintainer script. */
  private static String settingIn(String script, String name) {
    return script
        .lines()
        .filter(line -> line.startsWith(name + "="))
        .map(line -> line.substring(name.length() + 1))
        .findFirst()
        .orElse("");
  }

  private String removalCase() {
    return between(postrm, "    remove)", "    purge)");
  }

  private String purgeCase() {
    return between(postrm, "    purge)", "    upgrade|failed-upgrade");
  }

  private static String between(String script, String from, String to) {
    int start = script.indexOf(from);
    int end = script.indexOf(to);
    if (start < 0 || end < start) {
      throw new IllegalStateException("the postrm no longer has a " + from.strip() + " case");
    }
    return script.substring(start, end);
  }

  private static String fileNameOf(String documentation) {
    return documentation.substring(documentation.lastIndexOf('/') + 1);
  }

  private static String valueOf(List<String> unit, String key) {
    return unit.stream()
        .filter(line -> line.startsWith(key))
        .map(line -> line.substring(key.length()))
        .findFirst()
        .orElse("");
  }

  private static List<String> lines(String file) {
    return read(file).lines().map(String::strip).toList();
  }

  private static String read(String file) {
    try {
      return Files.readString(ShippedInstaller.directory().resolve(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
