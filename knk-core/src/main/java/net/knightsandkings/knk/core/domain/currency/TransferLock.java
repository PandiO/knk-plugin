package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;

/**
 * Whether staff locked a player's transfers ({@code /knk currency lock|unlock}, currency-payments
 * Phase 4): a locked player can neither send nor receive /pay; system grants still arrive.
 */
public record TransferLock(int userId, String username, boolean locked, String reason, Instant lockedAt) {
}
