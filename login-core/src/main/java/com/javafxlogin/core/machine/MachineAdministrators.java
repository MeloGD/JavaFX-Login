package com.javafxlogin.core.machine;

import com.javafxlogin.core.ipc.Peer;
import java.nio.file.FileSystems;

/**
 * Who this machine's operating system treats as one of its own administrators.
 *
 * <p>It answers about a {@link Peer}, never about an Account: a MachineAdministrator holds no Role
 * and is not a member of this system at all. The one question it exists for is whether the person
 * at the other end of a connection may run the first-run wizard, so that a normal user cannot claim
 * the Administrator on a fresh install.
 *
 * <p>It is an interface because that question is the seam Seam 1 tests both sides of. A suite that
 * had to arrange real group membership could only assert whatever the machine running it happens to
 * be configured with, which is not a property of this code.
 */
public interface MachineAdministrators {

  /** Whether the operating system treats {@code peer} as an administrator of this machine. */
  boolean includes(Peer peer);

  /**
   * The answer for the platform this process is running on.
   *
   * <p>The Windows path is designed and unbuilt, as the listening channel's is: its answer comes
   * from the peer's token rather than from a group database, and the service it would serve cannot
   * start on that platform yet either.
   *
   * @throws UnsupportedOperationException on a platform whose group database this build cannot read
   */
  static MachineAdministrators forCurrentPlatform() {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return new PosixMachineAdministrators();
    }
    throw new UnsupportedOperationException(
        "No way to tell who administers a "
            + System.getProperty("os.name")
            + "; on Windows this is the peer's token rather than a group database");
  }
}
