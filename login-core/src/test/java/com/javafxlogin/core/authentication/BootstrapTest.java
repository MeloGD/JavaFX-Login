package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskIfBootstrapNeeded;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
import com.javafxlogin.core.ipc.BootstrapNeeded;
import com.javafxlogin.core.ipc.Denied;
import com.javafxlogin.core.ipc.ErrorCode;
import com.javafxlogin.core.ipc.ErrorResponse;
import com.javafxlogin.core.ipc.Granted;
import com.javafxlogin.core.ipc.Ok;
import com.javafxlogin.core.ipc.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: creating the single Administrator, refusing to create a second one, and refusing anyone
 * who does not administer the machine.
 */
class BootstrapTest {

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void openService() {
    harness = ServiceHarness.cheap(directory);
  }

  @AfterEach
  void closeService() {
    harness.close();
  }

  @Test
  void createsTheSingleAdministratorWhenNoneExists() {
    Response response = harness.bootstrap("wren.holloway", "Correct-Horse-1");

    assertInstanceOf(Ok.class, response);
  }

  /**
   * No recovery key, no backup code, no enrolment token, no backdoor. The success answer is empty
   * and equal to every other one, which is the assertion: a later ticket that put anything into it
   * to hand back to the person would fail here rather than ship.
   */
  @Test
  void issuesNothingAlongsideTheAdministratorItCreated() {
    Response response = harness.bootstrap("wren.holloway", "Correct-Horse-1");

    assertEquals(new Ok(), response);
  }

  @Test
  void theAdministratorItCreatedCanAuthenticate() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    Response response =
        harness.send(
            new Authenticate("wren.holloway", "Correct-Horse-1".toCharArray(), Role.ADMINISTRATOR));

    assertInstanceOf(Granted.class, response);
  }

  @Test
  void isRefusedOnceAnAdministratorExists() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    Response response =
        harness.send(new Bootstrap("finch.mercer", "Another-Horse-2".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.ADMINISTRATOR_EXISTS, error.code());
  }

  @Test
  void theRefusalSurvivesAServiceRestart() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    harness.restart();
    Response response =
        harness.send(new Bootstrap("finch.mercer", "Another-Horse-2".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.ADMINISTRATOR_EXISTS, error.code());
  }

  @Test
  void theSecondAdministratorIsNotCreatedByTheRefusedAttempt() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");
    harness.send(new Bootstrap("finch.mercer", "Another-Horse-2".toCharArray()));

    Response response =
        harness.send(
            new Authenticate("finch.mercer", "Another-Horse-2".toCharArray(), Role.ADMINISTRATOR));

    assertInstanceOf(Denied.class, response);
  }

  // --- who may create it -------------------------------------------------------------------

  /**
   * The guard that keeps a normal user from claiming the Administrator on a fresh install. There is
   * no password to prove anything with here — this is the request that creates the first one — so
   * the operating system's word about the peer is the whole of the authorisation.
   */
  @Test
  void isRefusedWhenThePeerDoesNotAdministerTheMachine() {
    Response response =
        harness.sendFrom(
            ServiceHarness.ORDINARY_PEER,
            new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
  }

  /** A peer the operating system will not name is refused, never given the benefit of the doubt. */
  @Test
  void isRefusedWhenTheOperatingSystemWillNotNameThePeer() {
    Response response =
        harness.sendFromAnUnnamedPeer(
            new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
  }

  @Test
  void theAdministratorIsNotCreatedByARefusedPeer() {
    harness.sendFrom(
        ServiceHarness.ORDINARY_PEER,
        new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));

    Response response =
        harness.send(
            new Authenticate("wren.holloway", "Correct-Horse-1".toCharArray(), Role.ADMINISTRATOR));

    assertInstanceOf(Denied.class, response);
  }

  /**
   * Who is asking is settled before what the store holds, so a peer with no business here is told
   * the same thing on a fresh install as on one that was set up years ago.
   */
  @Test
  void asksWhoIsThereBeforeItLooksForAnAdministrator() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    Response response =
        harness.sendFrom(
            ServiceHarness.ORDINARY_PEER,
            new Bootstrap("finch.mercer", "Another-Horse-2".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
  }

  /** The refusal costs nothing that would tell a stopwatch whether the name was any good. */
  @Test
  void aRefusedPeerIsToldNothingAboutThePolicy() {
    Response response =
        harness.sendFrom(
            ServiceHarness.ORDINARY_PEER, new Bootstrap("root", "short".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
  }

  // --- which window a client opens ----------------------------------------------------------

  @Test
  void saysTheBootstrapIsNeededWhileNoAdministratorExists() {
    Response response = harness.send(new AskIfBootstrapNeeded());

    assertTrue(assertInstanceOf(BootstrapNeeded.class, response).needed());
  }

  @Test
  void saysTheBootstrapIsNotNeededOnceTheAdministratorExists() {
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    Response response = harness.send(new AskIfBootstrapNeeded());

    assertFalse(assertInstanceOf(BootstrapNeeded.class, response).needed());
  }

  /**
   * Answered for anyone who can reach the socket. A client has to know which window to open before
   * it knows anything else, and the answer is what a fresh install shows the moment it opens one.
   */
  @Test
  void answersWhichWindowToOpenEvenToAPeerThatMayNotRunTheWizard() {
    Response response = harness.sendFrom(ServiceHarness.ORDINARY_PEER, new AskIfBootstrapNeeded());

    assertTrue(assertInstanceOf(BootstrapNeeded.class, response).needed());
  }

  /** Being told the wizard is needed is not being allowed to run it. */
  @Test
  void aPeerToldTheBootstrapIsNeededIsStillRefusedTheBootstrap() {
    assertTrue(
        assertInstanceOf(
                BootstrapNeeded.class,
                harness.sendFrom(ServiceHarness.ORDINARY_PEER, new AskIfBootstrapNeeded()))
            .needed());

    Response response =
        harness.sendFrom(
            ServiceHarness.ORDINARY_PEER,
            new Bootstrap("wren.holloway", "Correct-Horse-1".toCharArray()));

    ErrorResponse error = assertInstanceOf(ErrorResponse.class, response);
    assertEquals(ErrorCode.NOT_MACHINE_ADMINISTRATOR, error.code());
  }
}
