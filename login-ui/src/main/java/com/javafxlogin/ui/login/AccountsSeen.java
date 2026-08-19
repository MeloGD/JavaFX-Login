package com.javafxlogin.ui.login;

import com.javafxlogin.core.account.AccountSummary;
import java.util.List;
import java.util.Objects;

/**
 * The Accounts of this deployment, as the Administrator asking is allowed to see them.
 *
 * <p>Each carries a name, a Role, the coarse PasswordStrength band, a language preference and
 * whether the Account is locked out. No password material of any kind: the summaries are built
 * inside the privileged process out of a query that names its columns, so there is no field a hash
 * could have travelled in.
 *
 * @param accounts the Accounts, in the order the service listed them, which is the order a person
 *     reads them
 */
public record AccountsSeen(List<AccountSummary> accounts) implements AccountListing {

  public AccountsSeen {
    accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
  }
}
