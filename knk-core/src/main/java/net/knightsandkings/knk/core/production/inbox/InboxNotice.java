package net.knightsandkings.knk.core.production.inbox;

import java.time.Instant;

/**
 * The payload of an {@code InboxUpdated} player notification (PandiO/knk-workspace#60): items arrived ({@code Delivered},
 * with what was lost at the cap and the near-cap warning level passed), items expire soon ({@code ExpiryReminder}) or
 * expired ({@code Expired}). The inbox itself is the record; a lost notice loses no items.
 */
public record InboxNotice(String event, Long deliveryId, String reason, long inboxedUnits, long lostUnits,
                          Integer warningPercent, int fillPercent, long capUnits, long expiringUnits, Instant expiresAt) {

    public static final String DELIVERED = "Delivered";
    public static final String EXPIRY_REMINDER = "ExpiryReminder";
    public static final String EXPIRED = "Expired";

    public boolean delivered() {
        return DELIVERED.equalsIgnoreCase(event);
    }

    public boolean reminder() {
        return EXPIRY_REMINDER.equalsIgnoreCase(event);
    }

    public boolean expired() {
        return EXPIRED.equalsIgnoreCase(event);
    }
}
