package com.javafxlogin.core.ipc;

import com.javafxlogin.core.account.AccountSummary;
import java.util.List;
import java.util.Objects;

/**
 * Every Account, as the administration panel lists them.
 *
 * <p>This is the only response that says anything about an Account other than the one asking, and
 * it is the reason {@link AccountSummary} exists as its own type: what crosses here is a name, a
 * Role, a coarse band, a language preference and a Lockout, and there is no field a password hash
 * could travel in even if some later build were careless with the query behind it.
 *
 * <p>It is never empty in practice — a deployment that can ask this has an Administrator, who is on
 * the list — but nothing here refuses an empty one: a store that has just been created is not a
 * malformed message.
 *
 * @param accounts the Accounts, in the order a person reads them
 */
public record AccountsListed(List<AccountSummary> accounts) implements Response {

  public AccountsListed {
    accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
  }
}
