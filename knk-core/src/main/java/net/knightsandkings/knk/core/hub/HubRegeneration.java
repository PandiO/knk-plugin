package net.knightsandkings.knk.core.hub;

import net.knightsandkings.knk.core.domain.settings.KnkHubRules;

/**
 * The hub's healing and feeding (hub DESIGN §13, §16.5), Bukkit-free. With the default rules it matches vanilla
 * 1.21.10 Peaceful ({@code ServerPlayer.tickRegeneration}): +1 health every 20 ticks, +1 saturation every 20 ticks up to
 * 20, +1 food every 10 ticks below 20. The caller runs it only inside the selected hub's region, only while
 * {@code naturalRegeneration} is on, and not in a world that is already Peaceful (vanilla regenerates there itself).
 */
public final class HubRegeneration {

    public static final int MAX_FOOD = 20;
    public static final float MAX_SATURATION = 20f;

    /** A player's health and hunger. */
    public record Vitals(double health, double maxHealth, int food, float saturation) {
    }

    private HubRegeneration() {
    }

    /** Whether anything is due at {@code tick} (the server tick count), so the caller can skip the region lookups. */
    public static boolean anythingDue(KnkHubRules rules, long tick) {
        return due(rules.healAmount(), rules.healIntervalTicks(), tick)
            || due(rules.feedAmount(), rules.feedIntervalTicks(), tick)
            || due(rules.saturationAmount(), rules.saturationIntervalTicks(), tick);
    }

    /** The player's vitals after {@code tick}'s healing and feeding; the same values when nothing is due. */
    public static Vitals tick(KnkHubRules rules, long tick, Vitals vitals) {
        double health = vitals.health();
        int food = vitals.food();
        float saturation = vitals.saturation();
        if (due(rules.healAmount(), rules.healIntervalTicks(), tick) && health > 0 && health < vitals.maxHealth()) {
            health = Math.min(vitals.maxHealth(), health + rules.healAmount());
        }
        if (due(rules.saturationAmount(), rules.saturationIntervalTicks(), tick) && saturation < MAX_SATURATION) {
            saturation = (float) Math.min(MAX_SATURATION, saturation + rules.saturationAmount());
        }
        if (due(rules.feedAmount(), rules.feedIntervalTicks(), tick) && food < MAX_FOOD) {
            food = Math.min(MAX_FOOD, food + rules.feedAmount());
        }
        return new Vitals(health, vitals.maxHealth(), food, saturation);
    }

    private static boolean due(double amount, int interval, long tick) {
        return amount > 0 && interval > 0 && tick % interval == 0;
    }
}
