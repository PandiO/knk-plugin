package net.knightsandkings.knk.paper.navigation.engine;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code navigation.destination-engine} in config.yml (destination engine DESIGN § 9, E17, phase 3,
 * PandiO/knk-workspace#69): whether {@code /navigate}, its tab completion, the navigation compass and
 * {@code NavigationService} use the destination engine. Default {@code false}: today's paths, unchanged. Read once at
 * start-up (a change needs a restart). Removed with the old paths in phase 4b (#747).
 *
 * @param enabled the engine is used
 */
public record DestinationEngineConfig(boolean enabled) {

    public static final String KEY = "navigation.destination-engine";

    public static DestinationEngineConfig defaults() {
        return new DestinationEngineConfig(false);
    }

    /** From the root of config.yml; a missing key or section means off. */
    public static DestinationEngineConfig from(ConfigurationSection root) {
        return root == null ? defaults() : new DestinationEngineConfig(root.getBoolean(KEY, false));
    }
}
