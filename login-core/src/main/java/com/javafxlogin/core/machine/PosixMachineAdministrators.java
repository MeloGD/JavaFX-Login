package com.javafxlogin.core.machine;

import com.javafxlogin.core.ipc.Peer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The answer a Unix-like machine gives: {@code root}, and whoever belongs to a group that
 * administers the machine.
 *
 * <p>The group names are the ones the target platforms use — {@code sudo} on Debian and Ubuntu,
 * {@code wheel} on Fedora and Arch, {@code admin} on macOS and older Debian. They are fixed here
 * rather than configured, because a deployment that could name its own administrative group could
 * name one it controls, and this is the check that decides who claims the Administrator.
 *
 * <p>The database is the machine's own local file. That is a real limit and is stated rather than
 * hidden: a machine whose administrators come from a directory service will not find them here, and
 * the person installing has to be root or a local administrator. It is the safe direction to be
 * wrong in — the failure is a wizard refused, not a wizard handed to a stranger — and it keeps a
 * process running as root from spawning a subprocess to ask.
 *
 * <p>Every failure to read is an exclusion, {@code root} apart. A file that cannot be read is not
 * an answer, and reading the absence of one as permission would hand the wizard to whoever could
 * make the file unreadable.
 */
public final class PosixMachineAdministrators implements MachineAdministrators {

  private static final Path GROUP_DATABASE = Paths.get("/etc/group");

  /** The account the kernel makes an administrator whatever the group database says. */
  private static final String ROOT = "root";

  private static final Set<String> ADMINISTRATIVE_GROUPS = Set.of(ROOT, "sudo", "wheel", "admin");

  private final Path groupDatabase;

  /** Reads the group database where this platform keeps it. */
  public PosixMachineAdministrators() {
    this(GROUP_DATABASE);
  }

  /** Reads a group database named explicitly, which is how the suite tests both answers. */
  PosixMachineAdministrators(Path groupDatabase) {
    this.groupDatabase = Objects.requireNonNull(groupDatabase, "groupDatabase");
  }

  @Override
  public boolean includes(Peer peer) {
    Objects.requireNonNull(peer, "peer");
    return ROOT.equals(peer.userName())
        || ADMINISTRATIVE_GROUPS.contains(peer.primaryGroupName())
        || belongsToAnAdministrativeGroup(peer.userName());
  }

  private boolean belongsToAnAdministrativeGroup(String userName) {
    for (String entry : read()) {
      // group:password:gid:member,member — a name matches whole or not at all, so that a
      // peer called "wren" is not admitted by an administrator called "wrenford".
      String[] fields = entry.split(":", -1);
      if (fields.length == 4
          && ADMINISTRATIVE_GROUPS.contains(fields[0])
          && List.of(fields[3].split(",", -1)).contains(userName)) {
        return true;
      }
    }
    return false;
  }

  private List<String> read() {
    try {
      return Files.readAllLines(groupDatabase, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Missing, unreadable, or not text this build can read. All three are the same
      // non-answer, and the non-answer excludes.
      return List.of();
    }
  }
}
