package net.knightsandkings.knk.core.survival.purchase;

import net.knightsandkings.knk.core.survival.SurvivalPoint;

import java.util.UUID;

/**
 * Purchase points (DESIGN §8, S44, S58; S4-16): fixed offers of blueprint × quantity at a price, bought by a living
 * member within the point's interaction range during waves and breaks alike. No stock and no per-player limit in v1.
 */
public final class PurchaseRules {

    public static final double DEFAULT_INTERACTION_RANGE = 4.0;

    private PurchaseRules() {
    }

    /** Whether a player at {@code player} is within {@code range} blocks of the point (a non-positive range = default). */
    public static boolean withinRange(SurvivalPoint point, SurvivalPoint player, double range) {
        if (point == null || player == null) return false;
        double r = range > 0 ? range : DEFAULT_INTERACTION_RANGE;
        return point.distanceTo(player) <= r;
    }

    /** The charge id of one purchase attempt (retried with the same id, never paid twice). */
    public static String chargeId(int pointId, int offerId, UUID unique) {
        return "buy-p" + pointId + "-o" + offerId + "-" + unique;
    }

    /** Splits {@code quantity} items into stacks of at most {@code maxStackSize}. */
    public static int[] stacks(int quantity, int maxStackSize) {
        int q = Math.max(1, quantity);
        int max = Math.max(1, maxStackSize);
        int full = q / max;
        int rest = q % max;
        int[] out = new int[full + (rest > 0 ? 1 : 0)];
        for (int i = 0; i < full; i++) out[i] = max;
        if (rest > 0) out[full] = rest;
        return out;
    }
}
