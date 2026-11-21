package net.knightsandkings.knk.paper.production.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import net.knightsandkings.knk.core.production.shop.display.DisplaySettings;
import net.knightsandkings.knk.paper.production.SurveyConfig;

/**
 * Reads {@code production.enabled} and {@code production.shops.displays.*} (display stalls P12,
 * PandiO/knk-workspace#250). A missing block means the defaults, and a missing {@code production.enabled} means off (A21).
 */
public final class ShopDisplayConfig {

    public static final String SECTION = "production.shops.displays";

    private ShopDisplayConfig() {
    }

    public static DisplaySettings read(FileConfiguration config) {
        if (config == null) {
            return DisplaySettings.defaults(false);
        }
        boolean enabled = config.getBoolean(SurveyConfig.ENABLED_KEY, false);
        ConfigurationSection s = config.getConfigurationSection(SECTION);
        if (s == null) {
            return DisplaySettings.defaults(enabled);
        }
        return new DisplaySettings(enabled,
                s.getInt("refresh-minutes", DisplaySettings.DEFAULT_REFRESH_MINUTES),
                s.getInt("selection-refresh-seconds", DisplaySettings.DEFAULT_SELECTION_REFRESH_SECONDS),
                s.getInt("best-seller-hours", DisplaySettings.DEFAULT_BEST_SELLER_HOURS),
                s.getDouble("item-scale", DisplaySettings.DEFAULT_ITEM_SCALE),
                s.getBoolean("label", true),
                s.getBoolean("hints", true));
    }
}
