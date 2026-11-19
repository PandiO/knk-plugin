package net.knightsandkings.knk.paper.production;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.survey.SurveySettings;

/**
 * Reads {@code production.enabled} and {@code production.surveys.*} (PandiO/knk-workspace#679). A missing block means the
 * defaults, and a missing {@code production.enabled} means off (A21): an existing server's {@code config.yml} never gets
 * new keys ({@code saveDefaultConfig()}).
 */
public final class SurveyConfig {

    public static final String ENABLED_KEY = "production.enabled";
    public static final String SECTION = "production.surveys";

    private SurveyConfig() {
    }

    public static SurveySettings read(FileConfiguration config) {
        if (config == null) {
            return SurveySettings.defaults(false);
        }
        boolean enabled = config.getBoolean(ENABLED_KEY, false);
        ConfigurationSection s = config.getConfigurationSection(SECTION);
        if (s == null) {
            return SurveySettings.defaults(enabled);
        }
        return new SurveySettings(enabled,
                s.getInt("poll-minutes", SurveySettings.DEFAULT_POLL_MINUTES),
                s.getInt("batch", SurveySettings.DEFAULT_BATCH),
                s.getInt("chunks-per-tick", SurveySettings.DEFAULT_CHUNKS_PER_TICK),
                s.getLong("max-area-blocks", SurveySettings.DEFAULT_MAX_AREA_BLOCKS),
                s.getInt("max-area-chunks", SurveySettings.DEFAULT_MAX_AREA_CHUNKS),
                s.getBoolean("scheduled-loads-chunks", false),
                s.getInt("notable-change-blocks", SurveySettings.DEFAULT_NOTABLE_CHANGE_BLOCKS),
                s.getInt("notable-cooldown-minutes", SurveySettings.DEFAULT_NOTABLE_COOLDOWN_MINUTES),
                s.getInt("areas-refresh-minutes", SurveySettings.DEFAULT_AREAS_REFRESH_MINUTES));
    }
}
