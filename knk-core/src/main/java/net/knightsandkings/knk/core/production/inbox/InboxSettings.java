package net.knightsandkings.knk.core.production.inbox;

/**
 * {@code production.enabled} and {@code production.inbox.*} (PandiO/knk-workspace#684). With {@code enabled} off nothing
 * of the inbox runs: kits and lootboxes keep today's delivery (leftovers dropped at the player's feet, A21) and
 * {@code /inbox} answers that the inbox is off. {@code joinSummary}: tell a player who joins with items in their inbox.
 */
public record InboxSettings(boolean enabled, boolean joinSummary) {

    public static InboxSettings defaults(boolean enabled) {
        return new InboxSettings(enabled, true);
    }
}
