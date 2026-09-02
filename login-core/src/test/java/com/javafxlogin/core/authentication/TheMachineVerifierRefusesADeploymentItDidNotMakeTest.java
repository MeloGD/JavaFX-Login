package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code verify-on-a-machine.sh} does when it is pointed at a deployment.
 *
 * <p>That script purges the product as one of its steps, which destroys every Account and its
 * password, the SecretVault, the configuration and the record of every authentication ever
 * attempted. It is meant for a machine that can be thrown away, and the difference between that
 * machine and somebody's is one line in a terminal. So it refuses to run where there is a
 * deployment it did not make itself, and the mark it leaves on the ones it did make is the whole of
 * how it tells them apart.
 *
 * <p>No machine is touched here: the subject is the refusal, and a directory in {@code /tmp} stands
 * in for the one at {@code /var/lib/javafx-login}.
 */
class TheMachineVerifierRefusesADeploymentItDidNotMakeTest {

  /** What the script leaves in a deployment of its own, and looks for before it destroys one. */
  private static final String MARK = ".made-by-verify-on-a-machine";

  @TempDir private Path directory;

  @Test
  void aDeploymentNothingHereMadeIsWhereItStops() throws Exception {
    Path deployment = aDeploymentSomebodyElseMade();

    WhatItDid ran = pointedAt(deployment);

    assertNotEquals(0, ran.status(), "it agreed to destroy a deployment it did not make");
    assertTrue(
        ran.output().contains(deployment.toString()),
        "the refusal does not name the deployment it is about: " + ran.output());
  }

  @Test
  void aDeploymentItMadeIsOneItMayDestroyAgain() throws Exception {
    // The second run on the same throwaway machine, and the first one's mark is still there.
    Path deployment = aDeploymentSomebodyElseMade();
    Files.writeString(deployment.resolve(MARK), "made by verify-on-a-machine.sh\n");

    WhatItDid ran = pointedAt(deployment);

    assertEquals(0, ran.status(), "it refused a deployment of its own:\n" + ran.output());
  }

  @Test
  void aMachineWithNoDeploymentOnItHasNothingToRefuse() throws Exception {
    WhatItDid ran = pointedAt(directory.resolve("never-existed"));

    assertEquals(0, ran.status(), "it refused a machine with nothing on it:\n" + ran.output());
  }

  @Test
  void anEmptyStateDirectoryIsNotADeployment() throws Exception {
    // What a first installation leaves: the directory is the package's and it is empty, because
    // ADR-0017 says only the FirstRunWizard makes a deployment. There is nothing here to lose.
    WhatItDid ran = pointedAt(Files.createDirectory(directory.resolve("state")));

    assertEquals(
        0, ran.status(), "it refused an installation that holds nothing:\n" + ran.output());
  }

  @Test
  void aMarkWithNothingBesideItIsStillNothingToLose() throws Exception {
    // A run that was interrupted between the installation and the bootstrap. Its mark is there
    // and no Account ever was.
    Path deployment = Files.createDirectory(directory.resolve("state"));
    Files.writeString(deployment.resolve(MARK), "made by verify-on-a-machine.sh\n");

    WhatItDid ran = pointedAt(deployment);

    assertEquals(0, ran.status(), "it refused its own empty directory:\n" + ran.output());
  }

  /** A directory holding what a deployment holds, and no mark saying this script made it. */
  private Path aDeploymentSomebodyElseMade() throws IOException {
    Path deployment = Files.createDirectory(directory.resolve("state"));
    Files.writeString(deployment.resolve("credentials.db"), "SQLite format 3\0");
    Files.writeString(deployment.resolve("secrets.db"), "SQLite format 3\0");
    Files.writeString(deployment.resolve("authentication-events.csv"), "");
    return deployment;
  }

  /**
   * Sources the shipped verifier and asks the one function that decides whether this machine may be
   * destroyed, about the directory named.
   */
  private WhatItDid pointedAt(Path deployment) throws IOException, InterruptedException {
    ProcessBuilder bash =
        new ProcessBuilder(
            "bash",
            "-c",
            """
            source "$1"
            refuse_a_deployment_this_script_did_not_make "$2"
            """,
            "verify-on-a-machine.sh",
            ShippedInstaller.directory().resolve("verify-on-a-machine.sh").toString(),
            deployment.toString());

    Process ran = bash.redirectErrorStream(true).start();
    String output = new String(ran.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(ran.waitFor(30, TimeUnit.SECONDS), "verify-on-a-machine.sh did not finish");
    return new WhatItDid(ran.exitValue(), output);
  }

  /** What one run of it ended as: how it exited, and what it said. */
  private record WhatItDid(int status, String output) {}
}
