package net.knightsandkings.knk.core.production.inbox;

import java.time.Instant;
import java.util.List;

/**
 * A player's reward inbox as the API shows it ({@code GET api/Inbox/{userId}}, PandiO/knk-workspace#60 and #684, production
 * DESIGN § 4.6): what the {@code /inbox} view lists. {@code storageId} is null until the first item arrives.
 */
public record InboxView(int userId, Integer storageId, long usedUnits, long capUnits, int fillPercent, Integer warningPercent,
                        Integer premiumTierGroupId, boolean locked, List<Entry> entries) {

    public InboxView {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** An empty inbox for {@code userId} (no storage yet). */
    public static InboxView empty(int userId) {
        return new InboxView(userId, null, 0, 0, 0, null, null, false, List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Units in all entries (not weighted: what the player would get). */
    public long totalAmount() {
        long total = 0;
        for (Entry entry : entries) {
            total += entry.amount();
        }
        return total;
    }

    /** The first moment anything in the inbox expires, or null. */
    public Instant nextExpiry() {
        Instant first = null;
        for (Entry entry : entries) {
            if (entry.nextExpiresAt() != null && (first == null || entry.nextExpiresAt().isBefore(first))) {
                first = entry.nextExpiresAt();
            }
        }
        return first;
    }

    /**
     * One entry: an amount of a blueprint, or one unique item ({@code itemInstanceId}). {@code nextExpiresAt} is when the
     * oldest part expires and {@code nextExpiringAmount} how much of it.
     */
    public record Entry(int itemBlueprintId, String name, Long itemInstanceId, String customDisplayName, long amount,
                        Instant nextExpiresAt, long nextExpiringAmount) {

        public boolean unique() {
            return itemInstanceId != null;
        }

        /** The name to show: the custom one of a unique item, else the blueprint's, else "Item #id". */
        public String displayName() {
            if (customDisplayName != null && !customDisplayName.isBlank()) {
                return customDisplayName;
            }
            return name != null && !name.isBlank() ? name : "Item #" + itemBlueprintId;
        }
    }
}
