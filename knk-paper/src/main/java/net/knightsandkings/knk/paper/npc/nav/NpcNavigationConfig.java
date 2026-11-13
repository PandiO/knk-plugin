package net.knightsandkings.knk.paper.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Overrides;

/**
 * {@code npc.navigation} in {@code config.yml} (PandiO/knk-workspace#359): global switches and the global, world and
 * region layers of {@link NavigationSettings}. Bad entries are collected in {@link #rejected()} for a startup warning.
 *
 * @param enabled          NPC navigation on at all (default off)
 * @param maxPlansInFlight route plans running at once, server-wide
 */
public record NpcNavigationConfig(boolean enabled, int maxPlansInFlight, NavigationSettings settings,
                                  List<String> rejected) {

    public static final String PATH = "npc.navigation";

    public static NpcNavigationConfig disabled() {
        return new NpcNavigationConfig(false, 4, NavigationSettings.defaults(), List.of());
    }

    /** Reads the section; null (no section) means disabled with code defaults. */
    public static NpcNavigationConfig from(ConfigurationSection section) {
        if (section == null) {
            return disabled();
        }
        List<String> rejected = new ArrayList<>();
        NavigationSettings.Builder builder = NavigationSettings.builder();
        builder.global(Overrides.parse(perNpcValues(section), PATH, rejected));
        ConfigurationSection worlds = section.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String world : worlds.getKeys(false)) {
                builder.world(world, Overrides.parse(perNpcValues(worlds.getConfigurationSection(world)),
                    PATH + ".worlds." + world, rejected));
            }
        }
        ConfigurationSection regions = section.getConfigurationSection("regions");
        if (regions != null) {
            for (String region : regions.getKeys(false)) {
                builder.region(region, Overrides.parse(perNpcValues(regions.getConfigurationSection(region)),
                    PATH + ".regions." + region, rejected));
            }
        }
        int plans = section.getInt("max-plans-in-flight", 4);
        if (plans < 1 || plans > 64) {
            rejected.add(PATH + ".max-plans-in-flight: " + plans + " is outside 1..64 (using 4)");
            plans = 4;
        }
        return new NpcNavigationConfig(section.getBoolean("enabled", false), plans, builder.build(),
            List.copyOf(rejected));
    }

    /** The section's direct values except the global switches and the nested layers. */
    private static Map<String, Object> perNpcValues(ConfigurationSection section) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (key.equals("enabled") || key.equals("max-plans-in-flight") || key.equals("worlds")
                || key.equals("regions") || section.isConfigurationSection(key)) {
                continue;
            }
            out.put(key, section.get(key));
        }
        return out;
    }
}
