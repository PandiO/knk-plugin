package net.knightsandkings.knk.paper.production.terminal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.terminal.TerminalSettings;
import net.knightsandkings.knk.paper.production.SurveyConfig;

/**
 * Reads {@code production.enabled} and {@code production.storage.*} (PandiO/knk-workspace#74). A missing block means the
 * defaults, and a missing {@code production.enabled} means off (A21).
 */
public final class StorageTerminalConfig {

    public static final String SECTION = "production.storage";

    private StorageTerminalConfig() {
    }

    public static TerminalSettings read(FileConfiguration config) {
        if (config == null) {
            return TerminalSettings.defaults(false);
        }
        boolean enabled = config.getBoolean(SurveyConfig.ENABLED_KEY, false);
        ConfigurationSection s = config.getConfigurationSection(SECTION);
        if (s == null) {
            return TerminalSettings.defaults(enabled);
        }
        return new TerminalSettings(enabled,
                s.getInt("refresh-minutes", TerminalSettings.DEFAULT_REFRESH_MINUTES),
                s.getInt("display-refresh-seconds", TerminalSettings.DEFAULT_DISPLAY_REFRESH_SECONDS));
    }
}
