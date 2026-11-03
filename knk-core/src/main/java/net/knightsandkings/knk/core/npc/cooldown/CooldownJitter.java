package net.knightsandkings.knk.core.npc.cooldown;

import java.util.random.RandomGenerator;

/**
 * Optional random extension of a cooldown so a crowd of identical mobs does not attack in lockstep.
 * <b>Off by default</b> (the design leaves jitter open; flag S2-7). When on, up to
 * {@code maxFraction × cooldown} ticks are <em>added</em> (never subtracted), so the resolved floors
 * still hold.
 *
 * @param enabled     whether jitter is applied at all
 * @param maxFraction the largest extension as a fraction of the cooldown (0.1 = up to 10 %)
 */
public record CooldownJitter(boolean enabled, double maxFraction) {

    public static final double DEFAULT_MAX_FRACTION = 0.1;
    public static final CooldownJitter OFF = new CooldownJitter(false, DEFAULT_MAX_FRACTION);

    public CooldownJitter {
        if (!Double.isFinite(maxFraction) || maxFraction < 0) {
            throw new IllegalArgumentException("maxFraction must be ≥ 0: " + maxFraction);
        }
    }

    public static CooldownJitter on(double maxFraction) {
        return new CooldownJitter(true, maxFraction);
    }

    /** The cooldown with this jitter applied; unchanged when off or the fraction is 0. */
    public int apply(int cooldownTicks, RandomGenerator random) {
        if (!enabled || maxFraction == 0.0 || cooldownTicks <= 0) return cooldownTicks;
        int extra = (int) Math.round(random.nextDouble() * maxFraction * cooldownTicks);
        return cooldownTicks + extra;
    }
}
