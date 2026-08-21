package com.javafxlogin.core.backup;

import com.javafxlogin.core.account.BackedUpAccount;
import com.javafxlogin.core.account.Role;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a Backup carries, in the clear, on either side of the encryption.
 *
 * <p>It exists on the inside of {@link BackupFile} and nowhere else: it is what the CredentialStore
 * handed over before the file was sealed, and what came back out of one after the password opened
 * it. Nothing hands one of these to a client — what crosses the socket is a {@link Backup}, which is
 * two numbers.
 *
 * <p>What is <em>not</em> here is as much of the design as what is. There is no SecretVault, because
 * ADR-0006 keeps it out: a Vault that travelled would be every secret this deployment holds sitting
 * in a file whose only protection is a password somebody typed in a hurry, and the DataKey is
 * wrapped for Operators who do not exist on the machine being restored onto anyway. There is no
 * MachineKey and no EventChain key, for the same reason and one more — they are the machine's, and
 * the record of AuthenticationEvents they protect stays on the machine that wrote it. And there is
 * no Enrolment: see {@link BackedUpAccount}.
 *
 * @param schemaVersion the CredentialStore schema the Accounts below are shaped like. It travels so
 *     that a restore is never a guess: a Backup that says a different number is refused rather than
 *     read hopefully, because the alternative is inserting rows into columns that mean something
 *     else now
 * @param accounts every Account that holds a password, in name order
 * @param configuration every configured setting, by name
 */
public record BackupContents(
    int schemaVersion, List<BackedUpAccount> accounts, Map<String, String> configuration) {

  public BackupContents {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(configuration, "configuration");
    accounts = List.copyOf(accounts);
    configuration = Map.copyOf(configuration);
  }

  /** What one of these came to, which is all a client is ever told about it. */
  public Backup summary() {
    return new Backup(accounts.size(), configuration.size());
  }

  /**
   * Whether restoring this would leave somebody able to administer the deployment.
   *
   * <p>Asked before anything is written, because the answer no is unrecoverable: a store with no
   * Administrator has no way back — the FirstRunWizard is offered only while none exists, and this
   * one would have been replaced by a set of Accounts that cannot create one. A Backup this build
   * wrote always names one; a file that does not is one somebody made, and it is refused rather than
   * restored.
   */
  public boolean namesAnAdministrator() {
    return accounts.stream().anyMatch(account -> account.role() == Role.ADMINISTRATOR);
  }
}
