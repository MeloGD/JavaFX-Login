package com.javafxlogin.core.vault;

import com.javafxlogin.core.auth.Argon2Parameters;
import com.javafxlogin.core.store.NumberedMigrations;
import com.javafxlogin.core.store.OwnerOnlyFiles;
import com.javafxlogin.core.store.SchemaTooNewException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The named store of secrets a ProtectedFeature needs but must not hold in the clear.
 *
 * <p>A separate file from the CredentialStore and owned by the same service, for the reasons ADR-0004
 * gives: the two answer different questions, face different attackers and change at different rates.
 * Like the store, it is created and reasserted owner-only, so the unprivileged account the graphical
 * client runs as can read neither a secret nor a wrapped key.
 *
 * <p><b>How it opens is the whole point.</b> Nothing here takes a boolean saying that authentication
 * succeeded. A Vault is opened by {@link #unlockFor}, which derives a key from the password somebody
 * just typed and tries to unwrap that Account's copy of the DataKey with it. A patched binary that
 * skips every check in this product still has to produce those thirty-two bytes, and it cannot.
 *
 * <p>The DataKey is shared by every Operator and wrapped once per Operator, plus once more under the
 * {@link MachineKey}. That second copy is what lets {@link #wrapFor} provision somebody with nobody
 * present, and it is also — stated plainly, as ADR-0005 insists — why the Administrator's exclusion
 * from the Vault is least privilege rather than a boundary. Whoever holds the Administrator password
 * can create an Operator, enrol it, and read everything in here. What they cannot do is reach it
 * without those two events being written to a record they cannot edit.
 *
 * <p>Not thread-safe: it holds one JDBC connection and is used from inside the AuthenticationService's
 * monitor, which serialises every request.
 */
public final class SecretVault implements AutoCloseable {

  private static final NumberedMigrations MIGRATIONS =
      NumberedMigrations.of("the SecretVault", List.of("db/vault/V001__secret_vault.sql"));

  /** Argon2id's own recommendation, and what every wrap in this file carries. */
  private static final int SALT_BYTES = 16;

  private final Path file;
  private final Connection connection;
  private final MachineKey machineKey;
  private final Argon2Parameters parameters;
  private final SecureRandom random;

  private SecretVault(
      Path file,
      Connection connection,
      MachineKey machineKey,
      Argon2Parameters parameters,
      SecureRandom random) {
    this.file = file;
    this.connection = connection;
    this.machineKey = machineKey;
    this.parameters = parameters;
    this.random = random;
  }

  /**
   * Opens the Vault at the given path, creating it — and the DataKey it exists to protect — if it
   * is not there yet.
   *
   * <p>The DataKey is made when the file is made, wrapped under the MachineKey and never again
   * generated. Doing it here rather than at the first enrolment is what keeps every later operation
   * one shape: an enrolment always rewraps a key that already exists, and there is no first Operator
   * who is special.
   *
   * @param file where the Vault lives, which is beside the CredentialStore and not in it
   * @param machineKeyFile where the second wrapped copy's key lives, beside the Vault
   * @param parameters the Argon2id cost a wrap made from now on is derived at
   * @throws SchemaTooNewException if the Vault was written by a build that understood more
   * @throws VaultException if the file or the key cannot be created or opened
   */
  public static SecretVault openOrCreate(
      Path file, Path machineKeyFile, Argon2Parameters parameters) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(machineKeyFile, "machineKeyFile");
    Objects.requireNonNull(parameters, "parameters");

    SecureRandom random = new SecureRandom();
    createOwnerOnly(file);
    Connection connection = connect(file);
    try {
      MIGRATIONS.applyTo(connection);
      SecretVault vault =
          new SecretVault(
              file, connection, MachineKey.readOrCreate(machineKeyFile, random), parameters, random);
      vault.createTheDataKeyIfThereIsNoneYet();
      return vault;
    } catch (SQLException | IOException e) {
      throw closeAfter(connection, new VaultException("could not open the SecretVault at " + file, e));
    } catch (RuntimeException e) {
      throw closeAfter(connection, e);
    }
  }

  /**
   * Opens this Account's Vault with the password somebody just typed, or answers that it does not
   * open.
   *
   * <p>Empty means one of two things and deliberately does not say which: this Account holds no
   * wrapped copy of the DataKey, or the password does not derive the key that unwraps the copy it
   * holds. The caller has already verified the password against the CredentialStore by the time it
   * gets here, so in practice the first is an Account provisioned before this Vault existed and the
   * second is a Vault file somebody has edited. Neither is something a client is told apart.
   */
  public Optional<UnlockedVault> unlockFor(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    Optional<Wrap> wrap = wrapOf(accountName);
    if (wrap.isEmpty()) {
      return Optional.empty();
    }
    KeyEncryptionKey kek =
        KeyEncryptionKey.derivedFrom(password, wrap.get().salt(), wrap.get().parameters());
    try {
      return AesGcm.open(kek.material(), wrap.get().nonce(), wrap.get().wrappedDataKey())
          .map(DataKey::of)
          .map(dataKey -> new UnlockedVault(this, accountName, dataKey, random));
    } finally {
      kek.destroy();
    }
  }

  /**
   * Wraps the DataKey for an Account under a key derived from the password it has just been given,
   * replacing whatever it held before.
   *
   * <p>This is what completing an enrolment does, and it is the one operation that needs the
   * MachineKey: the person whose password this is has never had Vault access before, so there is no
   * Operator copy to unwrap and nobody present who could open one. The DataKey is borrowed from the
   * machine's copy, wrapped once more, and destroyed before this returns — the Administrator who
   * asked for the enrolment never sees it, and neither does anything outside this package.
   *
   * @throws VaultException if the machine's copy of the DataKey cannot be read, which is a Vault
   *     whose key file has been removed or replaced
   */
  public void wrapFor(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");
    DataKey dataKey = theMachinesCopyOfTheDataKey();
    try {
      writeWrapFor(accountName, dataKey, password);
    } finally {
      dataKey.destroy();
    }
  }

  /**
   * Destroys an Account's wrapped copy of the DataKey, which is how revocation is made real.
   *
   * <p>Called when an Operator is deleted, and when an Administrator takes their password away: a
   * wrap under a password that no longer authenticates is no use to its holder, and leaving it there
   * would mean the old password still opened the Vault after the reset that was supposed to end it.
   *
   * @return whether there was one to destroy
   */
  public boolean destroyWrapFor(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM data_key_wraps WHERE account_name = ?")) {
      statement.setString(1, accountName);
      return statement.executeUpdate() > 0;
    } catch (SQLException e) {
      throw new VaultException("could not destroy a wrapped DataKey in " + file, e);
    }
  }

  /** Whether this Account holds a wrapped copy of the DataKey at all. */
  public boolean holdsAWrapFor(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    return wrapOf(accountName).isPresent();
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new VaultException("could not close the SecretVault at " + file, e);
    }
  }

  /** The secret under that name as the file holds it, still encrypted. */
  Optional<AesGcm.Sealed> sealedSecretNamed(String name) {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT nonce, ciphertext FROM secrets WHERE name = ?")) {
      statement.setString(1, name);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new AesGcm.Sealed(results.getBytes("nonce"), results.getBytes("ciphertext")));
      }
    } catch (SQLException e) {
      throw new VaultException("could not read a secret in " + file, e);
    }
  }

  /** Writes a secret under that name, replacing whatever was kept under it before. */
  void keepSealedSecret(String name, AesGcm.Sealed sealed) {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO secrets (name, nonce, ciphertext, kept_at) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (name) DO UPDATE SET nonce = excluded.nonce,"
                + " ciphertext = excluded.ciphertext, kept_at = excluded.kept_at")) {
      statement.setString(1, name);
      statement.setBytes(2, sealed.nonce());
      statement.setBytes(3, sealed.ciphertext());
      statement.setString(4, now());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new VaultException("could not keep a secret in " + file, e);
    }
  }

  /**
   * Writes an Account's wrap of a DataKey that is already in hand, under a fresh salt and the cost
   * this Vault was opened with.
   *
   * <p>A fresh salt every time, including on a rewrap of the same key for the same Account: a salt
   * reused across two wraps would mean the same password derived the same key twice, and there is
   * nothing to be gained by keeping the old one.
   */
  void writeWrapFor(String accountName, DataKey dataKey, char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    KeyEncryptionKey kek = KeyEncryptionKey.derivedFrom(password, salt, parameters);
    try {
      AesGcm.Sealed wrapped = AesGcm.seal(kek.material(), dataKey.material(), random);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO data_key_wraps (account_name, kdf_salt, kdf_memory_kib, kdf_iterations,"
                  + " kdf_parallelism, nonce, wrapped_data_key, wrapped_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT (account_name) DO UPDATE SET kdf_salt = excluded.kdf_salt,"
                  + " kdf_memory_kib = excluded.kdf_memory_kib,"
                  + " kdf_iterations = excluded.kdf_iterations,"
                  + " kdf_parallelism = excluded.kdf_parallelism, nonce = excluded.nonce,"
                  + " wrapped_data_key = excluded.wrapped_data_key,"
                  + " wrapped_at = excluded.wrapped_at")) {
        statement.setString(1, accountName);
        statement.setBytes(2, salt);
        statement.setInt(3, parameters.memoryKib());
        statement.setInt(4, parameters.iterations());
        statement.setInt(5, parameters.parallelism());
        statement.setBytes(6, wrapped.nonce());
        statement.setBytes(7, wrapped.ciphertext());
        statement.setString(8, now());
        statement.executeUpdate();
      }
    } catch (SQLException e) {
      throw new VaultException("could not wrap the DataKey in " + file, e);
    } finally {
      kek.destroy();
    }
  }

  /**
   * The DataKey, out of the copy wrapped under the MachineKey. The caller destroys what comes back.
   *
   * @throws VaultException if that copy does not open, which means the key file beside this one is
   *     not the key this Vault was written under — a Vault nobody can provision against again
   */
  private DataKey theMachinesCopyOfTheDataKey() {
    AesGcm.Sealed wrapped =
        machineWrap()
            .orElseThrow(
                () ->
                    new VaultException(
                        "the SecretVault at "
                            + file
                            + " holds no machine copy of the DataKey"));
    return AesGcm.open(machineKey.material(), wrapped.nonce(), wrapped.ciphertext())
        .map(DataKey::of)
        .orElseThrow(
            () ->
                new VaultException(
                    "the key beside the SecretVault at " + file + " does not open it"));
  }

  private void createTheDataKeyIfThereIsNoneYet() {
    if (machineWrap().isPresent()) {
      return;
    }
    DataKey dataKey = DataKey.generate(random);
    try {
      AesGcm.Sealed wrapped = AesGcm.seal(machineKey.material(), dataKey.material(), random);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO machine_wrap (id, nonce, wrapped_data_key, wrapped_at)"
                  + " VALUES (1, ?, ?, ?)")) {
        statement.setBytes(1, wrapped.nonce());
        statement.setBytes(2, wrapped.ciphertext());
        statement.setString(3, now());
        statement.executeUpdate();
      }
    } catch (SQLException e) {
      throw new VaultException("could not create the DataKey in " + file, e);
    } finally {
      dataKey.destroy();
    }
  }

  private Optional<AesGcm.Sealed> machineWrap() {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT nonce, wrapped_data_key FROM machine_wrap");
        ResultSet results = statement.executeQuery()) {
      if (!results.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new AesGcm.Sealed(results.getBytes("nonce"), results.getBytes("wrapped_data_key")));
    } catch (SQLException e) {
      throw new VaultException("could not read the machine's copy of the DataKey in " + file, e);
    }
  }

  private Optional<Wrap> wrapOf(String accountName) {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT kdf_salt, kdf_memory_kib, kdf_iterations, kdf_parallelism, nonce,"
                + " wrapped_data_key FROM data_key_wraps WHERE account_name = ?")) {
      statement.setString(1, accountName);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Wrap(
                results.getBytes("kdf_salt"),
                new Argon2Parameters(
                    results.getInt("kdf_memory_kib"),
                    results.getInt("kdf_iterations"),
                    results.getInt("kdf_parallelism"),
                    AesGcm.KEY_BYTES),
                results.getBytes("nonce"),
                results.getBytes("wrapped_data_key")));
      }
    } catch (SQLException | IllegalArgumentException e) {
      throw new VaultException("could not read a wrapped DataKey in " + file, e);
    }
  }

  /**
   * One Operator's wrapped copy of the DataKey, exactly as the file holds it.
   *
   * <p>The parameters come from the row and not from this build's configuration, for the same reason
   * the authentication hash carries its own inside a PHC string: raising the cost must not invalidate
   * the wraps already written at the old one.
   */
  private record Wrap(
      byte[] salt, Argon2Parameters parameters, byte[] nonce, byte[] wrappedDataKey) {}

  private static String now() {
    return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static Connection connect(Path file) {
    try {
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (Statement statement = connection.createStatement()) {
        // As the CredentialStore is opened, and for the same reason: the service does not run
        // continuously, so a write must survive it stopping. Journal mode stays at the default
        // rather than WAL, which would leave a second file alongside carrying the same ciphertexts.
        statement.execute("PRAGMA foreign_keys = ON");
        statement.execute("PRAGMA synchronous = FULL");
      }
      return connection;
    } catch (SQLException e) {
      throw new VaultException("could not open the SecretVault at " + file, e);
    }
  }

  /** Owner-only before SQLite ever touches it: a file SQLite creates itself inherits the umask. */
  private static void createOwnerOnly(Path file) {
    try {
      OwnerOnlyFiles.createOrReassert(file);
    } catch (IOException e) {
      throw new VaultException("could not create the SecretVault at " + file, e);
    }
  }

  private static <E extends RuntimeException> E closeAfter(Connection connection, E failure) {
    try {
      connection.close();
    } catch (SQLException e) {
      failure.addSuppressed(e);
    }
    return failure;
  }
}
