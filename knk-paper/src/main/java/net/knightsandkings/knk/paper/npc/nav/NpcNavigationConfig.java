package net.knightsandkings.knk.paper.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Overrides;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;

/**
 * {@code npc.navigation} in {@code config.yml} (PandiO/knk-workspace#359): global switches and the global, world and
 * region layers of {@link NavigationSettings}. Bad entries are collected in {@link #rejected()} for a startup warning.
 *
 * @param enabled          NPC navigation on at all (default off)
 * @param maxPlansInFlight route plans running at once, server-wide
 * @param spawnsPerTick    NPC spawns per tick at most (NAV-13)
 * @param accessProfiles   the configured NPC access profiles by id (lower case); always has {@code default}
 * @param ruleChecksPerTick NPCs whose way ahead and position are checked against the current rules per tick at most
 *                         (PandiO/knk-workspace#361, NAV-13)
 */
public record NpcNavigationConfig(boolean enabled, int maxPlansInFlight, NavigationSettings settings,
                                  List<String> rejected, int spawnsPerTick,
                                  Map<String, NpcAccessProfile> accessProfiles, int ruleChecksPerTick) {

    /** Before PandiO/knk-workspace#361: ten rule checks per tick. */
    public NpcNavigationConfig(boolean enabled, int maxPlansInFlight, NavigationSettings settings,
                               List<String> rejected, int spawnsPerTick, Map<String, NpcAccessProfile> accessProfiles) {
        this(enabled, maxPlansInFlight, settings, rejected, spawnsPerTick, accessProfiles,
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.DEFAULT_RULE_CHECKS_PER_TICK);
    }

    /** The global switches: direct values of the section that are not per-NPC settings. */
    static final java.util.Set<String> GLOBAL_KEYS = java.util.Set.of("enabled", "max-plans-in-flight",
        "spawns-per-tick", "rule-checks-per-tick", "worlds", "regions", "access-profiles");

    public NpcNavigationConfig {
        Map<String, NpcAccessProfile> profiles = new java.util.LinkedHashMap<>();
        profiles.put(NpcAccessProfile.DEFAULT.id(), NpcAccessProfile.DEFAULT);
        if (accessProfiles != null) {
            profiles.putAll(accessProfiles);
        }
        accessProfiles = java.util.Collections.unmodifiableMap(profiles);
    }

    /** Before PandiO/knk-workspace#360: two spawns per tick, the default access profile only. */
    public NpcNavigationConfig(boolean enabled, int maxPlansInFlight, NavigationSettings settings,
                               List<String> rejected) {
        this(enabled, maxPlansInFlight, settings, rejected, 2, Map.of());
    }

    /** The profile of that name (any case), or null. */
    public NpcAccessProfile accessProfile(String id) {
        return id == null ? null : accessProfiles.get(id.toLowerCase(java.util.Locale.ROOT));
    }

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
        int spawns = section.getInt("spawns-per-tick", 2);
        if (spawns < 1 || spawns > 20) {
            rejected.add(PATH + ".spawns-per-tick: " + spawns + " is outside 1..20 (using 2)");
            spawns = 2;
        }
        int checks = section.getInt("rule-checks-per-tick",
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.DEFAULT_RULE_CHECKS_PER_TICK);
        if (checks < 1 || checks > 200) {
            rejected.add(PATH + ".rule-checks-per-tick: " + checks + " is outside 1..200 (using "
                + net.knightsandkings.knk.core.npc.nav.NpcNavigationService.DEFAULT_RULE_CHECKS_PER_TICK + ")");
            checks = net.knightsandkings.knk.core.npc.nav.NpcNavigationService.DEFAULT_RULE_CHECKS_PER_TICK;
        }
        Map<String, NpcAccessProfile> profiles = new java.util.LinkedHashMap<>();
        ConfigurationSection profileSection = section.getConfigurationSection("access-profiles");
        if (profileSection != null) {
            for (String id : profileSection.getKeys(false)) {
                ConfigurationSection profile = profileSection.getConfigurationSection(id);
                if (profile == null) {
                    rejected.add(PATH + ".access-profiles." + id + ": not a section");
                    continue;
                }
                for (String key : profile.getKeys(false)) {
                    if (!PROFILE_KEYS.contains(key)) {
                        rejected.add(PATH + ".access-profiles." + id + "." + key + ": unknown setting");
                    }
                }
                String name = id.toLowerCase(java.util.Locale.ROOT);
                profiles.put(name, new NpcAccessProfile(name, profile.getBoolean("bypass-domain-rules", false),
                    profile.getBoolean("open-doors", true),
                    new java.util.HashSet<>(profile.getStringList("allow-regions")),
                    new java.util.HashSet<>(profile.getStringList("deny-regions"))));
            }
        }
        return new NpcNavigationConfig(section.getBoolean("enabled", false), plans, builder.build(),
            List.copyOf(rejected), spawns, profiles, checks);
    }

    static final java.util.Set<String> PROFILE_KEYS = java.util.Set.of("bypass-domain-rules", "open-doors",
        "allow-regions", "deny-regions");

    /** The section's direct values except the global switches and the nested layers. */
    private static Map<String, Object> perNpcValues(ConfigurationSection section) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            if (GLOBAL_KEYS.contains(key) || section.isConfigurationSection(key)) {
                continue;
            }
            out.put(key, section.get(key));
        }
        return out;
    }
}
