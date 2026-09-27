package net.knightsandkings.knk.core.domain.users;

/**
 * One staff balance change as the API's ledger recorded it: the signed amount actually applied
 * (for a set, target minus the balance the server found), the balance before and after, and the
 * ledger transaction id. {@code replayed} = a retry of an earlier request; nothing changed again.
 */
public record BalanceChange(
    BalanceCurrency currency,
    BalanceOperation mode,
    long amount,
    long balanceBefore,
    long balanceAfter,
    String transactionId,
    boolean replayed
) {}
