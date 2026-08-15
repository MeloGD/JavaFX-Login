package com.javafxlogin.core.machine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javafxlogin.core.ipc.Peer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who the machine treats as one of its own administrators, read from a group database this test
 * writes rather than the one the machine running the suite happens to keep.
 *
 * <p>That is the whole reason the file is a parameter: an assertion about {@code /etc/group} would
 * pass or fail on whether the developer running it is in {@code sudo}, which is not a property of
 * this code.
 */
class PosixMachineAdministratorsTest {

  @TempDir Path directory;

  private MachineAdministrators administrators;

  @BeforeEach
  void writeAGroupDatabase() throws IOException {
    Path groupFile = directory.resolve("group");
    Files.write(
        groupFile,
        List.of(
            "root:x:0:",
            "sudo:x:27:wren.holloway,finch.mercer",
            "wheel:x:998:",
            "staff:x:50:wren.holloway",
            "users:x:100:wren.holloway,finch.mercer,mallory.quill"));
    administrators = new PosixMachineAdministrators(groupFile);
  }

  @Test
  void includesRoot() {
    assertTrue(administrators.includes(new Peer("root", "root")));
  }

  @Test
  void includesAMemberOfAnAdministrativeGroup() {
    assertTrue(administrators.includes(new Peer("wren.holloway", "wren.holloway")));
  }

  /** The primary group never appears in the member list of its own entry, so it is read apart. */
  @Test
  void includesSomeoneWhoseOwnPrimaryGroupIsAdministrative() {
    assertTrue(administrators.includes(new Peer("juno.vale", "wheel")));
  }

  @Test
  void excludesAnOrdinaryUser() {
    assertFalse(administrators.includes(new Peer("mallory.quill", "mallory.quill")));
  }

  /** Membership of a group that is not administrative is not membership of one that is. */
  @Test
  void excludesSomeoneWhoSharesAnOrdinaryGroupWithAnAdministrator() {
    assertFalse(administrators.includes(new Peer("mallory.quill", "users")));
  }

  @Test
  void excludesANameThatIsOnlyAPrefixOfAMembersName() {
    assertFalse(administrators.includes(new Peer("wren", "wren")));
  }

  /**
   * Fails closed. A database that cannot be read is not an answer, and reading the absence of one
   * as permission would hand the wizard to whoever could make the file unreadable.
   */
  @Test
  void excludesEveryoneWhenTheGroupDatabaseCannotBeRead() {
    MachineAdministrators missing =
        new PosixMachineAdministrators(directory.resolve("no-such-file"));

    assertFalse(missing.includes(new Peer("wren.holloway", "wren.holloway")));
  }

  /** Except root, whom the operating system makes an administrator whatever any file says. */
  @Test
  void stillIncludesRootWhenTheGroupDatabaseCannotBeRead() {
    MachineAdministrators missing =
        new PosixMachineAdministrators(directory.resolve("no-such-file"));

    assertTrue(missing.includes(new Peer("root", "root")));
  }

  /** A line that is not an entry is skipped rather than read as one with an empty name. */
  @Test
  void ignoresCommentsAndBlankLinesInTheGroupDatabase() throws IOException {
    Path groupFile = directory.resolve("odd-group");
    Files.write(groupFile, List.of("# the machine's groups", "", "sudo:x:27:wren.holloway"));
    MachineAdministrators odd = new PosixMachineAdministrators(groupFile);

    assertTrue(odd.includes(new Peer("wren.holloway", "wren.holloway")));
    assertFalse(odd.includes(new Peer("mallory.quill", "mallory.quill")));
  }
}
