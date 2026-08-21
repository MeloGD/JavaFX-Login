package com.javafxlogin.core.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.javafxlogin.core.harness.ServiceHarness;
import com.javafxlogin.core.ipc.AskWhichProtocolIsSpoken;
import com.javafxlogin.core.ipc.ProtocolSpoken;
import com.javafxlogin.core.ipc.ProtocolVersion;
import com.javafxlogin.core.ipc.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seam 1: the one question the AuthenticationService answers before anybody has said anything.
 *
 * <p>Issue #16 asks that a client and a service which disagree about the protocol say so as a
 * disagreement rather than as a parse failure. That is only possible if the question survives every
 * version there will ever be, so what is pinned here is that the service answers it at all, that it
 * answers with the version this build speaks, and that it answers it with no Session, no Account and
 * no store behind it — the three things a client has none of at the moment it needs an answer.
 */
class ProtocolVersionTest {

  @TempDir Path directory;

  private ServiceHarness harness;

  @BeforeEach
  void start() {
    harness = ServiceHarness.cheap(directory);
  }

  @AfterEach
  void stop() {
    harness.close();
  }

  @Test
  void theServiceSaysWhichProtocolItSpeaks() {
    Response response = harness.send(new AskWhichProtocolIsSpoken());

    assertEquals(
        new ProtocolSpoken(ProtocolVersion.CURRENT),
        assertInstanceOf(ProtocolSpoken.class, response));
  }

  /**
   * The question survives being asked before and after the deployment exists. A client asks it at
   * startup, when it has no Session because there is no Account to get one with, and the answer
   * must not start depending on what the store holds once something is in it.
   */
  @Test
  void itIsAnsweredTheSameOnAFreshInstallAsOnASetUpOne() {
    Response beforeAnybodyExists = harness.send(new AskWhichProtocolIsSpoken());
    harness.bootstrap("wren.holloway", "Correct-Horse-1");

    assertEquals(beforeAnybodyExists, harness.send(new AskWhichProtocolIsSpoken()));
  }
}
