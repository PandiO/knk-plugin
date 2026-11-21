package net.knightsandkings.knk.core.production.inbox;

import java.time.Instant;

/**
 * The API's answer to a delivery ({@code ItemDeliveryDto}): {@code status} Inboxed, PartlyLost or Lost; what reached the
 * inbox and what was lost at the hard cap (InboxOverflow); {@code replayed} when the key had been used before.
 */
public record DeliveryResult(long deliveryId, String status, long inboxedUnits, long lostUnits, long capUnits,
                             Instant expiresAt, boolean replayed) {
}
