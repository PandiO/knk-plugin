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
 * @param serverId                    this game server's id in the runtime-worlds report (KNG-118): each
 *                                    server's report replaces only its own worlds; never blank
 */
public record GameSettingsConfig(int refreshIntervalSeconds, int runtimeSyncIntervalSeconds, int backupHistoryLimit,
                                 String serverId) {

    public static final String DEFAULT_SERVER_ID = "default";

    public GameSettingsConfig {
        serverId = serverId == null || serverId.isBlank() ? DEFAULT_SERVER_ID : serverId.trim();
    }

    public GameSettingsConfig(int refreshIntervalSeconds, int runtimeSyncIntervalSeconds, int backupHistoryLimit) {
        this(refreshIntervalSeconds, runtimeSyncIntervalSeconds, backupHistoryLimit, DEFAULT_SERVER_ID);
    }

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
            Math.max(0, section.getInt("backup-history-limit", defaults.backupHistoryLimit())),
            section.getString("server-id", defaults.serverId()));
    }
}
