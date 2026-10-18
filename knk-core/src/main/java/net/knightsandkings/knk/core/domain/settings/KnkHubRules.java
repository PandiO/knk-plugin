package net.knightsandkings.knk.core.domain.settings;

/**
 * The hub's rules (hub DESIGN §13), applied inside the selected hub's region only. Defaults are the confirmed initial
 * values; healing and feeding default to vanilla 1.21.10 Peaceful (§16.5: +1 health and +1 saturation every 20 ticks,
 * +1 food every 10 ticks). Players always keep and use their own inventory.
 */
public record KnkHubRules(boolean damagePlayers, boolean damageEntities, boolean takeDamage, boolean buildBreak,
                          boolean dropPickup, boolean useDoors, boolean useNpcs, boolean useStorage,
                          boolean useItemFramesPaintings, double healAmount, int healIntervalTicks, int feedAmount,
                          int feedIntervalTicks, double saturationAmount, int saturationIntervalTicks) {

    public KnkHubRules {
        healAmount = Math.max(0, healAmount);
        feedAmount = Math.max(0, feedAmount);
        saturationAmount = Math.max(0, saturationAmount);
        healIntervalTicks = Math.max(1, healIntervalTicks);
        feedIntervalTicks = Math.max(1, feedIntervalTicks);
        saturationIntervalTicks = Math.max(1, saturationIntervalTicks);
    }

    public static KnkHubRules defaults() {
        return new KnkHubRules(false, false, false, false, true, true, true, false, false, 1.0, 20, 1, 10, 1.0, 20);
    }
}
