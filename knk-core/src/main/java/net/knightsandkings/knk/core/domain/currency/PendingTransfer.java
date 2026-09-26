package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * A payment waiting for the sender's confirmation (/pay at or above the server's confirmation
 * threshold, currency DESIGN.md §3.6). Nothing has moved yet; {@code /pay confirm <publicId>}
 * within {@link #expiresInSeconds()} sends it.
 */
public record PendingTransfer(
    String publicId,
    String status, // "Pending" | "Confirmed" | "Cancelled" | "Expired"
    BalanceCurrency currency,
    long amount,
    long fee,
    int recipientUserId,
    String recipientUsername,
    Instant expiresAt,
    int expiresInSeconds
) {
    public boolean isOpen() {
        return "Pending".equalsIgnoreCase(status) && expiresInSeconds > 0;
    }
}
