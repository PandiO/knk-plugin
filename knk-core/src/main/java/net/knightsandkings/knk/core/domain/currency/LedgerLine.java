package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * One change to a player's coins, gems or XP, as the ledger recorded it (/transactions and
 * /knk user &lt;player&gt; history, KNG-21 Phase 3 / KNG-23).
 */
public record LedgerLine(
    long entryId,
    String publicId,
    Instant createdAt,
    BalanceCurrency currency,
    long amount,          // signed
    long balanceBefore,
    long balanceAfter,
    String kind,          // Grant, Spend, Transfer, AdminAdjust, Reversal, Merge, ...
    String reasonCode,    // SALARY, PLAYER_TRANSFER, ADMIN_GRANT, ...
    String reason,
    String initiator,     // Player, Admin, System, PluginService
    String initiatorUsername,
    String initiatorComponent,
    Integer counterpartyUserId,
    String counterpartyUsername
) {
}
