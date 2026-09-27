package net.knightsandkings.knk.core.domain.currency;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * What the API did with a /pay (currency ledger, KNG-21 Phase 3): either the money moved
 * ({@link Status#COMPLETED}; {@code replayed} when a retry found it had already moved) or it waits
 * for confirmation ({@link Status#PENDING_CONFIRMATION}, see {@link #pending()}). Every number is
 * the server's; the plugin never computes a balance itself.
 */
public record TransferOutcome(
    Status status,
    String publicId,
    boolean replayed,
    BalanceCurrency currency,
    long amount,
    long fee,
    int senderUserId,
    String senderUsername,
    int recipientUserId,
    String recipientUsername,
    Balances senderBalances, // null if the API didn't send them
    PendingTransfer pending   // set when PENDING_CONFIRMATION
) {
    public enum Status { COMPLETED, PENDING_CONFIRMATION }

    public boolean completed() {
        return status == Status.COMPLETED;
    }
}
