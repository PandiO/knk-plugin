package net.knightsandkings.knk.paper.production;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.transport.LaneSettings;

/**
 * Reads {@code production.enabled} and {@code production.logistics.lanes.*} (PandiO/knk-workspace#681). A missing block
 * means the defaults, and a missing {@code production.enabled} means off (A21).
 */
public final class LaneConfig {

    public static final String SECTION = "production.logistics.lanes";

    private LaneConfig() {
    }

    public static LaneSettings read(FileConfiguration config) {
        if (config == null) {
            return LaneSettings.defaults(false);
        }
        boolean enabled = config.getBoolean(SurveyConfig.ENABLED_KEY, false);
        ConfigurationSection s = config.getConfigurationSection(SECTION);
        if (s == null) {
            return LaneSettings.defaults(enabled);
        }
        return new LaneSettings(enabled,
                s.getInt("interval-minutes", LaneSettings.DEFAULT_INTERVAL_MINUTES),
                s.getInt("batch", LaneSettings.DEFAULT_BATCH),
                s.getDouble("max-snap-distance", LaneSettings.DEFAULT_MAX_SNAP_DISTANCE),
                s.getBoolean("road-connections", true));
    }
}
