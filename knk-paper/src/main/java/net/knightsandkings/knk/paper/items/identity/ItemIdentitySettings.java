package net.knightsandkings.knk.paper.items.identity;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code items.identity} in config.yml (PandiO/knk-workspace#141). Both switches are off by default: with
 * {@code enabled: false} nothing of item identity runs and items are built and handled exactly as before.
 *
 * @param enabled               load the material map, stamp {@code knk_blueprint} in {@code BlueprintItemAssembler},
 *                              report conversion counts
 * @param convertOnObtain       the conversion hooks (pickup, drops, fishing, crafting, trades, first container open);
 *                              needs {@code enabled}
 * @param mapRefreshMinutes     how often the material map is fetched again
 * @param reportIntervalMinutes how often the conversion counts are sent to the API and logged
 */
public record ItemIdentitySettings(boolean enabled, boolean convertOnObtain, long mapRefreshMinutes, long reportIntervalMinutes) {

    public static final String PATH = "items.identity";
    public static final ItemIdentitySettings DISABLED = new ItemIdentitySettings(false, false, 10, 10);

    public static ItemIdentitySettings fromConfig(ConfigurationSection root) {
        ConfigurationSection section = root != null ? root.getConfigurationSection(PATH) : null;
        if (section == null) {
            return DISABLED;
        }
        boolean enabled = section.getBoolean("enabled", false);
        return new ItemIdentitySettings(
                enabled,
                enabled && section.getBoolean("convert-on-obtain", false),
                Math.max(1, section.getLong("map-refresh-minutes", 10)),
                Math.max(1, section.getLong("report-interval-minutes", 10)));
    }
}
