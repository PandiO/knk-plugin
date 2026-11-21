package net.knightsandkings.knk.paper.production.harvest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.harvest.HarvestSettings;
import net.knightsandkings.knk.paper.production.SurveyConfig;

/**
 * Reads {@code production.enabled} and {@code production.harvest.*} (PandiO/knk-workspace#134). A missing block means the
 * defaults, and a missing {@code production.enabled} means off (A21): an existing server's {@code config.yml} never gets
 * new keys ({@code saveDefaultConfig()}).
 */
public final class HarvestConfig {

    public static final String SECTION = "production.harvest";

    private HarvestConfig() {
    }

    public static HarvestSettings read(FileConfiguration config) {
        if (config == null) {
            return HarvestSettings.defaults(false);
        }
        boolean enabled = config.getBoolean(SurveyConfig.ENABLED_KEY, false);
        ConfigurationSection s = config.getConfigurationSection(SECTION);
        if (s == null) {
            return HarvestSettings.defaults(enabled);
        }
        ConfigurationSection m = s.getConfigurationSection("messages");
        HarvestSettings.Messages messages = m == null ? HarvestSettings.Messages.DEFAULT
                : new HarvestSettings.Messages(m.getString("denied"), m.getString("regenerating"), m.getString("paid"),
                        m.getString("rejected"));
        return new HarvestSettings(enabled,
                s.getInt("catalog-refresh-minutes", HarvestSettings.DEFAULT_CATALOG_REFRESH_MINUTES),
                s.getInt("report-flush-seconds", HarvestSettings.DEFAULT_REPORT_FLUSH_SECONDS),
                s.getInt("report-batch", HarvestSettings.DEFAULT_REPORT_BATCH),
                s.getInt("regen-blocks-per-tick", HarvestSettings.DEFAULT_REGEN_BLOCKS_PER_TICK),
                s.getInt("regen-retry-seconds", HarvestSettings.DEFAULT_REGEN_RETRY_SECONDS),
                s.getInt("save-seconds", HarvestSettings.DEFAULT_SAVE_SECONDS),
                s.getBoolean("deny-at-empty-pool", false),
                s.getInt("tree-max-logs", HarvestSettings.DEFAULT_TREE_MAX_LOGS),
                s.getInt("tree-max-leaves", HarvestSettings.DEFAULT_TREE_MAX_LEAVES),
                s.getInt("max-area-chunks", HarvestSettings.DEFAULT_MAX_AREA_CHUNKS),
                messages);
    }
}
