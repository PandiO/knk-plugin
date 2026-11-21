package net.knightsandkings.knk.paper.production.inbox;

import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.inbox.InboxSettings;
import net.knightsandkings.knk.paper.production.SurveyConfig;

/**
 * Reads {@code production.enabled} and {@code production.inbox.*} (PandiO/knk-workspace#684). A missing block means the
 * defaults, and a missing {@code production.enabled} means off (A21).
 */
public final class InboxConfig {

    public static final String SECTION = "production.inbox";

    private InboxConfig() {
    }

    public static InboxSettings read(FileConfiguration config) {
        if (config == null) {
            return InboxSettings.defaults(false);
        }
        boolean enabled = config.getBoolean(SurveyConfig.ENABLED_KEY, false);
        return new InboxSettings(enabled, config.getBoolean(SECTION + ".join-summary", true));
    }
}
