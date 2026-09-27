package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/** What a player may send right now (GET /api/currency/limits/{userId}); informational - /pay re-checks everything server-side. */
public record TransferLimits(
    int userId,
    BalanceCurrency currency,
    boolean transferable,
    long minTransfer,
    long maxTransfer,
    long dailySendCap,
    long sentLast24h,
    long remainingToday,
    long confirmThreshold,
    int transferFeeBasisPoints,
    Instant nextTransferAt,   // null = now
    boolean eligible,
    int minSenderAccountAgeHours,
    Instant eligibleFrom,     // null when the account-age rule is met
    String requiredTitleName,
    Integer requiredExperience,
    boolean locked
) {
}
