package net.knightsandkings.knk.paper.territory;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.territory.TerritoryCacheBuilder;
import net.knightsandkings.knk.core.territory.TerritorySettings;

/**
 * Reads {@code production.enabled} and {@code production.territory.*} (PandiO/knk-workspace#689). A missing block means
 * everything off (A21): an existing server's {@code config.yml} never gets new keys ({@code saveDefaultConfig()}).
 */
public final class TerritoryConfig {

    public static final String ENABLED_KEY = "production.enabled";
    public static final String SECTION = "production.territory";

    private TerritoryConfig() {
    }

    public static TerritorySettings read(FileConfiguration config) {
        if (config == null) {
            return TerritorySettings.disabled();
        }
        ConfigurationSection section = config.getConfigurationSection(SECTION);
        Map<String, String> messages = new HashMap<>();
        ConfigurationSection messageSection = section == null ? null : section.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                String value = messageSection.getString(key);
                if (value != null) {
                    messages.put(key, value);
                }
            }
        }
        return new TerritorySettings(
                config.getBoolean(ENABLED_KEY, false),
                section == null ? TerritorySettings.DEFAULT_REFRESH_MINUTES
                        : section.getInt("refresh-minutes", TerritorySettings.DEFAULT_REFRESH_MINUTES),
                section == null || section.getBoolean("notify-players", true),
                section == null || section.getBoolean("report", true),
                section != null && section.getBoolean("map-outlines", false),
                section == null ? TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD
                        : section.getInt("max-chunks-per-world", TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD),
                messages);
    }
}
