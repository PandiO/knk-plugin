package net.knightsandkings.knk.paper.settings;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code game-settings:} block of config.yml (docs/specs/game-settings/DESIGN.md §4). Every key
 * has a default, so an older config.yml without the block keeps working.
 *
 * @param refreshIntervalSeconds      how often the settings are read from the API (min 5)
 * @param runtimeSyncIntervalSeconds  how often the loaded worlds are checked and, when they changed, reported (min 5)
 * @param backupHistoryLimit          how many earlier versions of the settings to keep on disk; 0 keeps none
 */
public record GameSettingsConfig(int refreshIntervalSeconds, int runtimeSyncIntervalSeconds, int backupHistoryLimit) {

    public static GameSettingsConfig defaults() {
        return new GameSettingsConfig(30, 30, 48);
    }

    public static GameSettingsConfig from(FileConfiguration config) {
        GameSettingsConfig defaults = defaults();
        ConfigurationSection section = config == null ? null : config.getConfigurationSection("game-settings");
        if (section == null) {
            return defaults;
        }
        return new GameSettingsConfig(
            Math.max(5, section.getInt("refresh-interval-seconds", defaults.refreshIntervalSeconds())),
            Math.max(5, section.getInt("runtime-sync-interval-seconds", defaults.runtimeSyncIntervalSeconds())),
            Math.max(0, section.getInt("backup-history-limit", defaults.backupHistoryLimit())));
    }
}
