package net.knightsandkings.knk.core.domain.currency;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/** Payload of a PaymentReceived player notification: someone paid this player (shown now, or on their next join). */
public record PaymentNotice(
    long amount,
    BalanceCurrency currency,
    int fromUserId,
    String fromUsername,
    String transactionPublicId,
    long balanceAfter
) {
}
