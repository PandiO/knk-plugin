package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code regions.managed} section of config.yml, parsed from nested maps (so it needs no Bukkit). Anything malformed is
 * left out and reported in {@link #warnings()}; a bad entry never stops the rest, or the plugin, from loading.
 *
 * <pre>
 * regions.managed:
 *   startup-repair: { enabled: true, delay-ticks: 100, attempts: 3, retry-delay-seconds: 30, page-size: 100 }
 *   resource-production-block-break: false
 *   manage-global-region: false
 *   overrides:                       # by WorldGuard region id
 *     domain_47: { kind: resource-production, priority: 35, parent: district_2, flags: { block-break: allow } }
 *   extra-regions:                   # regions with no v3 domain (arenas, battlegrounds)
 *     - { region: arena_2, kind: arena, parent: district_2 }
 * </pre>
 */
public record ManagedRegionsConfig(
        boolean repairEnabled,
        int delayTicks,
        int attempts,
        int retryDelaySeconds,
        int pageSize,
        ManagedRegionPolicy.Options options,
        Map<String, RegionOverride> overrides,
        List<ManagedRegionSpec> extraRegions,
        List<String> warnings) {

    public static ManagedRegionsConfig defaults() {
        return fromMap(null);
    }

    @SuppressWarnings("unchecked")
    public static ManagedRegionsConfig fromMap(Map<String, Object> section) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> root = section != null ? section : Map.of();
        Map<String, Object> repair = map(root.get("startup-repair"));

        Map<String, RegionOverride> overrides = new LinkedHashMap<>();
        map(root.get("overrides")).forEach((regionId, raw) -> {
            Map<String, Object> entry = map(raw);
            ManagedRegionKind kind = null;
            if (entry.get("kind") != null) {
                kind = ManagedRegionKind.parse(String.valueOf(entry.get("kind"))).orElse(null);
                if (kind == null) {
                    warnings.add("overrides." + regionId + ": unknown kind '" + entry.get("kind") + "'; ignored");
                }
            }
            Integer priority = entry.get("priority") instanceof Number n ? n.intValue() : null;
            String parent = entry.get("parent") != null ? String.valueOf(entry.get("parent")) : null;
            overrides.put(regionId, new RegionOverride(kind, parent, priority, flagRules(map(entry.get("flags")), regionId, warnings)));
        });

        List<ManagedRegionSpec> extras = new ArrayList<>();
        Object rawExtras = root.get("extra-regions");
        if (rawExtras instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> entry = map(item);
                Object region = entry.get("region");
                Object kindName = entry.get("kind");
                ManagedRegionKind kind = kindName != null ? ManagedRegionKind.parse(String.valueOf(kindName)).orElse(null) : null;
                if (region == null || kind == null) {
                    warnings.add("extra-regions: entry needs a 'region' and a known 'kind' (got " + item + "); ignored");
                    continue;
                }
                Object parent = entry.get("parent");
                extras.add(new ManagedRegionSpec(String.valueOf(region), kind, parent != null ? String.valueOf(parent) : null,
                        "config extra-regions"));
            }
        }

        return new ManagedRegionsConfig(
                bool(repair.get("enabled"), true),
                Math.max(0, integer(repair.get("delay-ticks"), 100)),
                Math.max(1, integer(repair.get("attempts"), 3)),
                Math.max(1, integer(repair.get("retry-delay-seconds"), 30)),
                Math.max(1, integer(repair.get("page-size"), 100)),
                new ManagedRegionPolicy.Options(bool(root.get("resource-production-block-break"), false),
                        bool(root.get("manage-global-region"), false)),
                overrides, extras, warnings);
    }

    private static List<FlagRule> flagRules(Map<String, Object> flags, String regionId, List<String> warnings) {
        List<FlagRule> rules = new ArrayList<>();
        flags.forEach((name, raw) -> {
            Object value = raw instanceof String text && (text.equalsIgnoreCase("allow") || text.equalsIgnoreCase("deny"))
                    ? FlagState.valueOf(text.toUpperCase(Locale.ROOT)) : raw;
            if (value instanceof String || value instanceof Integer || value instanceof FlagState) {
                rules.add(FlagRule.seed(name.toLowerCase(Locale.ROOT), value));
            } else {
                warnings.add("overrides." + regionId + ".flags." + name + ": only allow/deny, text and whole numbers are supported; ignored");
            }
        });
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
