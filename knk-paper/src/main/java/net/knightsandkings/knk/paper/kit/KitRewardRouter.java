package net.knightsandkings.knk.paper.kit;

import java.util.List;

import org.bukkit.entity.Player;

/**
 * Kit delivery through the reward inbox (PandiO/knk-workspace#684, production DESIGN § 4.6): set on the kit flows only
 * while {@code production.enabled} is on. Without one every kit path places and drops exactly as before (#261, A21).
 */
public interface KitRewardRouter {

    /**
     * Main thread: places {@code items} for an online player like {@link KitGrantPlacer#place}; what doesn't fit goes to
     * the player's reward inbox under {@code idempotencyKey} (dropped at their feet as before when the API refuses).
     */
    KitGrantPlacer.PlacementSummary place(Player player, int userId, List<KitGrantPlacer.ResolvedItem> items,
                                          String idempotencyKey, String kitName);

    /** The player is offline: every item goes to their reward inbox. Any thread. */
    void deliverOffline(int userId, List<KitGrantPlacer.ResolvedItem> items, String idempotencyKey, String kitName);
}
