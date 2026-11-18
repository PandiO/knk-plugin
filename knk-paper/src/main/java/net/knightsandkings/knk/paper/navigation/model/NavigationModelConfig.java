package net.knightsandkings.knk.paper.navigation.model;

import java.time.Duration;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.navigation.model.ModelFreshness;

/**
 * The navigation model's settings in config.yml (destination engine DESIGN § 7.3, § 9, E12;
 * PandiO/knk-workspace#103): {@code navigation.destinations.stale-after-days}, how old the model may get before the
 * destination lists say "may be out of date". Default 14; values below 1 count as 1. Kept apart from
 * {@code NavigationConfig} so the road navigation record is untouched.
 *
 * @param staleAfterDays the staleness limit in days (at least 1)
 */
public record NavigationModelConfig(int staleAfterDays) {

    public static final String STALE_AFTER_DAYS = "navigation.destinations.stale-after-days";

    public NavigationModelConfig {
        staleAfterDays = Math.max(1, staleAfterDays);
    }

    public static NavigationModelConfig defaults() {
        return new NavigationModelConfig(ModelFreshness.DEFAULT_STALE_AFTER_DAYS);
    }

    /** From the root of config.yml; a missing key or section means the default. */
    public static NavigationModelConfig from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new NavigationModelConfig(root.getInt(STALE_AFTER_DAYS, ModelFreshness.DEFAULT_STALE_AFTER_DAYS));
    }

    public Duration staleAfter() {
        return Duration.ofDays(staleAfterDays);
    }
}
