package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.javafxlogin.core.account.Role;
import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.Authenticate;
import com.javafxlogin.core.ipc.Bootstrap;
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

/** Seam 1: creating the single Administrator, and refusing to create a second one. */
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
}
