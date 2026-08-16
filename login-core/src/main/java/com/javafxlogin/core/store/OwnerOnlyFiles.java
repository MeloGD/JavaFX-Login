package com.javafxlogin.core.store;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The files the AuthenticationService owns, created so that only the account it runs as can read
 * them.
 *
 * <p>This is where ADR-0002's boundary is actually enforced: not in a check written in Java, but in
 * the mode the operating system holds against the file. Every file the privileged process writes —
 * the CredentialStore and the record of AuthenticationEvents alike — is created through here, so
 * that a second one added later cannot quietly be the one that inherits the umask.
 */
public final class OwnerOnlyFiles {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");

  private OwnerOnlyFiles() {}

  /**
   * Creates the file, and the directory holding it, owner-only — and reasserts the mode on one that
   * already exists, so that an upgrade cannot silently loosen it.
   *
   * <p>Creating the file first matters where something else opens it afterwards: a file SQLite
   * creates itself inherits the umask.
   *
   * @throws IOException if the file or its directory cannot be created
   */
  public static void createOrReassert(Path file) throws IOException {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    Path directory = file.toAbsolutePath().getParent();
    if (directory != null && !Files.exists(directory)) {
      if (posix) {
        Files.createDirectories(
            directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
      } else {
        Files.createDirectories(directory);
      }
    }
    if (!Files.exists(file)) {
      createOwnerOnly(file, posix);
      return;
    }
    if (posix) {
      Files.setPosixFilePermissions(file, OWNER_ONLY);
    } else {
      restrictWithoutPosix(file);
    }
  }

  /**
   * Creates a file that must not exist yet, owner-only, and creates no directory to hold it.
   *
   * <p>This is what an export is written through. It refuses an existing file rather than
   * reasserting the mode on it, because the privileged process writing to a path someone else chose
   * must not be a way to overwrite a file — including one reached through a symbolic link, which is
   * why the refusal is the operating system's and not a check made just before writing.
   *
   * @throws java.nio.file.FileAlreadyExistsException if something is already there
   * @throws IOException if the file cannot be created
   */
  public static void createNew(Path file) throws IOException {
    createOwnerOnly(file, FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  private static void createOwnerOnly(Path file, boolean posix) throws IOException {
    if (posix) {
      Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } else {
      Files.createFile(file);
      restrictWithoutPosix(file);
    }
  }

  /**
   * Best-effort narrowing where POSIX modes do not exist.
   *
   * <p>It deliberately does not fail when the platform refuses these calls, because it is not what
   * protects the file there: on Windows these files live inside a directory the installer has
   * already restricted by ACL, and {@code setReadable(false, false)} is documented to return false
   * rather than take effect. Failing here would stop the service starting at all instead of
   * starting it with a weaker mode.
   *
   * <p>Designed, unbuilt and unverified: no Windows machine exists for this project yet, and none
   * of this may be reported as working on one.
   */
  private static void restrictWithoutPosix(Path file) {
    java.io.File asFile = file.toFile();
    asFile.setReadable(false, false);
    asFile.setWritable(false, false);
    asFile.setReadable(true, true);
    asFile.setWritable(true, true);
  }
}
