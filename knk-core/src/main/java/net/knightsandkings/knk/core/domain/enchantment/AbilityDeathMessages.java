package net.knightsandkings.knk.core.domain.enchantment;

import java.util.Locale;
import java.util.Map;

/**
 * The death message for a player killed by an area ability (PandiO/knk-workspace#222). One pattern for every
 * enchantment, with an optional pattern per enchantment id (plugin {@code config.yml},
 * {@code custom-enchantments.death-messages}; developer decision 2026-11-03).
 * Placeholders: {@code %victim%}, {@code %player%} (the player who used the ability) and {@code %enchantment%} (its
 * display name).
 *
 * @param defaultPattern the pattern for an enchantment without its own
 * @param overrides      patterns per enchantment id ({@code chaos}, {@code flash_chaos}), lower-case
 */
public record AbilityDeathMessages(String defaultPattern, Map<String, String> overrides) {

    public static final String DEFAULT_PATTERN = "%victim% was killed with %enchantment% by %player%";

    public AbilityDeathMessages {
        defaultPattern = defaultPattern == null || defaultPattern.isBlank() ? DEFAULT_PATTERN : defaultPattern;
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /** The message with the placeholders filled in. */
    public String format(String enchantmentId, String victim, String player, String enchantmentName) {
        String id = enchantmentId == null ? "" : enchantmentId.toLowerCase(Locale.ROOT);
        String pattern = overrides.get(id);
        if (pattern == null || pattern.isBlank()) {
            pattern = defaultPattern;
        }
        return pattern
                .replace("%victim%", nonNull(victim))
                .replace("%player%", nonNull(player))
                .replace("%enchantment%", enchantmentName == null || enchantmentName.isBlank() ? id : enchantmentName);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
