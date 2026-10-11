package net.knightsandkings.knk.core.statistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalDouble;

/**
 * Highest survived fall (DESIGN.md §F.9, D3): a fall counts when it dealt damage and the player
 * survived it - health plus absorption stays above the damage. The value is the fall distance in
 * blocks rounded to one decimal (half up). Pure.
 */
public final class FallRule {

    private FallRule() {
    }

    /**
     * @param fallDistance blocks fallen ({@code Entity#getFallDistance} at the damage event)
     * @param healthBefore health before the damage
     * @param finalDamage  the damage that is applied (after armour/effects)
     * @param absorption   absorption hearts before the damage
     * @return the fall to record, or empty when no damage was dealt, the fall was fatal or the distance is zero
     */
    public static OptionalDouble survivedFall(double fallDistance, double healthBefore, double finalDamage, double absorption) {
        if (!(finalDamage > 0) || !(fallDistance > 0) || Double.isInfinite(fallDistance)) {
            return OptionalDouble.empty();
        }
        double remaining = healthBefore + Math.max(0, absorption) - finalDamage;
        if (!(remaining > 0)) {
            return OptionalDouble.empty();
        }
        double rounded = BigDecimal.valueOf(fallDistance).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return rounded > 0 ? OptionalDouble.of(rounded) : OptionalDouble.empty();
    }
}
