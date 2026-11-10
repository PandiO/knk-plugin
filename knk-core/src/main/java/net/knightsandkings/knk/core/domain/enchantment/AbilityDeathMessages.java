package net.knightsandkings.knk.core.domain.enchantment;

import java.util.Locale;
import java.util.Map;

/**
 * The death message for a player killed by an area ability (PandiO/knk-workspace#222). One pattern for every
 * enchantment, with an optional pattern per enchantment id (plugin {@code config.yml},
 * {@code custom-enchantments.death-messages}; developer decision 2026-11-03).
 * Placeholders: {@code %victim%}, {@code %player%} (the player who used the ability), {@code %enchantment%} (its
 * display name) and {@code %item%} (the item it was used with, like vanilla's {@code using [Sword]}). {@code %item%}
 * stays in the text for the plugin to fill in as the item's name with its hover; when the item is unknown,
 * {@code " using %item%"} is left out.
 *
 * @param defaultPattern the pattern for an enchantment without its own
 * @param overrides      patterns per enchantment id ({@code chaos}, {@code flash_chaos}), lower-case
 */
public record AbilityDeathMessages(String defaultPattern, Map<String, String> overrides) {

    public static final String DEFAULT_PATTERN = "%victim% was killed with %enchantment% by %player% using %item%";
    public static final String ITEM_PLACEHOLDER = "%item%";

    public AbilityDeathMessages {
        defaultPattern = defaultPattern == null || defaultPattern.isBlank() ? DEFAULT_PATTERN : defaultPattern;
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    /** The message with every placeholder but {@code %item%} filled in, the item unknown (see the class comment). */
    public String format(String enchantmentId, String victim, String player, String enchantmentName) {
        return format(enchantmentId, victim, player, enchantmentName, false);
    }

    /**
     * The message with {@code %victim%}, {@code %player%} and {@code %enchantment%} filled in. With {@code hasItem},
     * {@code %item%} is kept for the caller to replace; without, {@code " using %item%"} (or a bare {@code %item%}) is
     * removed.
     */
    public String format(String enchantmentId, String victim, String player, String enchantmentName, boolean hasItem) {
        String id = enchantmentId == null ? "" : enchantmentId.toLowerCase(Locale.ROOT);
        String pattern = overrides.get(id);
        if (pattern == null || pattern.isBlank()) {
            pattern = defaultPattern;
        }
        if (!hasItem) {
            pattern = pattern.replace(" using " + ITEM_PLACEHOLDER, "").replace(ITEM_PLACEHOLDER, "");
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
