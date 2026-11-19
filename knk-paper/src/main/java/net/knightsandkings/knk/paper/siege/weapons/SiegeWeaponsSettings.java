package net.knightsandkings.knk.paper.siege.weapons;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The plugin's {@code siege.weapons} block (SIEGE_WEAPONS § 13.2.7, PandiO/knk-workspace#354). Everything of the
 * siege weapons feature stays off unless {@code siege.weapons.enabled} is true; with it off nothing is registered, so
 * Siege matches and world tasks behave exactly as before (A04).
 */
public record SiegeWeaponsSettings(boolean enabled) {

    public static final String ENABLED_PATH = "siege.weapons.enabled";

    public static SiegeWeaponsSettings defaults() {
        return new SiegeWeaponsSettings(false);
    }

    /** Reads the block from the root of {@code config.yml}; a missing block or key is off. */
    public static SiegeWeaponsSettings from(ConfigurationSection root) {
        if (root == null) return defaults();
        return new SiegeWeaponsSettings(root.getBoolean(ENABLED_PATH, false));
    }
}
