package com.javafxlogin.core.policy;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The rules about what an Account name and a password are allowed to be.
 *
 * <p>They live inside the AuthenticationService and are applied there, so a client that never
 * checked a password could still not create an Account with a weak one. The client asks the same
 * question the service will answer — see {@link #assess} — so that what a person is shown while
 * typing is what will happen when they submit, rather than a second implementation of these rules
 * that drifts from this one.
 *
 * <p>Two rules here look cosmetic and are not. A blocked name protects the account list ADR-0002
 * keeps unreadable; a coarse strength band exists so that a leaked store cannot be sorted by which
 * Account is cheapest to attack.
 */
public final class AccountPolicy {

  private static final String BLOCKED_NAMES_RESOURCE = "policy/blocked-account-names.txt";
  private static final String BREACHED_PASSWORDS_RESOURCE = "policy/breached-passwords.txt";

  private final AccountNameRules names;
  private final PasswordRules passwords;

  private AccountPolicy(AccountNameRules names, PasswordRules passwords) {
    this.names = names;
    this.passwords = passwords;
  }

  /**
   * The policy as it ships, from the lists packaged with the application. Package-private because a
   * deployment always has a file it may extend the blocklist with, whether or not it wrote one —
   * see {@link #bundledExtendedBy}.
   */
  static AccountPolicy bundled() {
    return alsoRefusing(List.of());
  }

  /**
   * The bundled policy plus the names in a file this deployment wrote, or the bundled policy alone
   * if there is no such file.
   *
   * <p>This is what makes the blocklist extensible without a rebuild. The file is read here, at
   * start-up: the service runs on demand and stops when it is idle, so an edit takes effect the
   * next time it is asked for something.
   *
   * @param blockedNames a file of one name per line, which need not exist
   */
  public static AccountPolicy bundledExtendedBy(Path blockedNames) {
    Objects.requireNonNull(blockedNames, "blockedNames");
    return alsoRefusing(PolicyResource.linesOfFileIfPresent(blockedNames));
  }

  private static AccountPolicy alsoRefusing(List<String> deploymentNames) {
    return new AccountPolicy(
        AccountNameRules.refusing(
            Stream.concat(
                    PolicyResource.linesOfBundledList(BLOCKED_NAMES_RESOURCE).stream(),
                    deploymentNames.stream())
                .toList()),
        PasswordRules.refusing(PolicyResource.linesOfBundledList(BREACHED_PASSWORDS_RESOURCE)));
  }

  /**
   * What the policy makes of a proposed name and password, together: every rule they break, and the
   * band to show the person choosing them.
   *
   * <p>Both are assessed on every call even when the name alone is already refused, because the
   * caller is the person typing and telling them one problem at a time is how a password ends up
   * being retyped five times.
   */
  public Assessment assess(String accountName, char[] password) {
    Objects.requireNonNull(accountName, "accountName");
    Objects.requireNonNull(password, "password");

    Assessment ofThePassword = assessPassword(password);
    Set<PolicyViolation> violations = EnumSet.noneOf(PolicyViolation.class);
    violations.addAll(names.violationsOf(accountName));
    violations.addAll(ofThePassword.violations());
    return new Assessment(List.copyOf(violations), ofThePassword.strength());
  }

  /**
   * What the policy makes of a proposed Account name on its own, which is what creating an Account
   * asks: an Administrator chooses the name and never the password, so there is no password to put
   * beside it.
   */
  public List<PolicyViolation> violationsOfName(String accountName) {
    Objects.requireNonNull(accountName, "accountName");
    return List.copyOf(names.violationsOf(accountName));
  }

  /**
   * What the policy makes of a proposed password on its own, which is what completing an enrolment
   * asks.
   *
   * <p>The name is deliberately left out of that decision. It belongs to an Account that already
   * exists and passed these rules when it was created, and somebody setting their first password
   * cannot change it — a deployment that extended its blocklist in the meantime would otherwise
   * leave an Operator refused for a reason they have no way to fix.
   */
  public Assessment assessPassword(char[] password) {
    Objects.requireNonNull(password, "password");
    return new Assessment(
        List.copyOf(passwords.violationsOf(password)), passwords.strengthOf(password));
  }
}
