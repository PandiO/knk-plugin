package net.knightsandkings.knk.core.worlds;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code worlds} section of config.yml (KNG-114, hub DESIGN §16 decision 1: a minimal KnK world loader instead of
 * Multiverse), parsed from nested maps so it needs no Bukkit. The worlds listed here are loaded at startup, next to the
 * {@code level-name} world (and its nether/end) that the server loads itself. A malformed entry is left out and reported
 * in {@link #warnings()}; it never stops the rest, or the plugin, from loading.
 *
 * <pre>
 * worlds:
 *   load:
 *     - name: hub            # the world folder (and Bukkit world name)
 *       environment: normal  # normal | nether | the_end
 *       generator: void      # default | flat | void - how new chunks are generated (every load)
 *       create: true         # create the world when its folder does not exist yet
 *       required: true       # players may not join while it is missing (operators still may)
 * </pre>
 */
public record KnkWorldsConfig(List<WorldSpec> worlds, List<String> warnings) {

    /** Bukkit world names are folder names: keep them to what every file system and command accepts. */
    private static final String NAME_PATTERN = "[A-Za-z0-9_\\-]{1,64}";

    public KnkWorldsConfig {
        worlds = List.copyOf(worlds);
        warnings = List.copyOf(warnings);
    }

    public static KnkWorldsConfig empty() {
        return new KnkWorldsConfig(List.of(), List.of());
    }

    public static KnkWorldsConfig fromMap(Map<String, Object> section) {
        List<String> warnings = new ArrayList<>();
        List<WorldSpec> worlds = new ArrayList<>();
        Object load = section != null ? section.get("load") : null;
        if (load == null) {
            return new KnkWorldsConfig(worlds, warnings);
        }
        if (!(load instanceof List<?> entries)) {
            warnings.add("worlds.load must be a list; no extra world is loaded");
            return new KnkWorldsConfig(worlds, warnings);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String where = "worlds.load[" + i + "]";
            Map<String, Object> entry = map(entries.get(i));
            Object rawName = entries.get(i) instanceof String text ? text : entry.get("name");
            String name = rawName == null ? "" : String.valueOf(rawName).trim();
            if (!name.matches(NAME_PATTERN)) {
                warnings.add(where + ": world name '" + name + "' is missing or not a plain folder name; skipped");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                warnings.add(where + ": world '" + name + "' is listed twice; the first entry is used");
                continue;
            }
            WorldSpec.Environment environment = parse(entry.get("environment"), WorldSpec.Environment.class,
                    WorldSpec.Environment.NORMAL, where + ".environment", warnings);
            WorldSpec.Generator generator = parse(entry.get("generator"), WorldSpec.Generator.class,
                    WorldSpec.Generator.DEFAULT, where + ".generator", warnings);
            boolean create = bool(entry.get("create"), false);
            boolean required = bool(entry.get("required"), true);
            worlds.add(new WorldSpec(name, environment, generator, create, required));
        }
        return new KnkWorldsConfig(worlds, warnings);
    }

    private static <E extends Enum<E>> E parse(Object raw, Class<E> type, E fallback, String where, List<String> warnings) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            warnings.add(where + ": unknown value '" + raw + "'; using " + fallback.name().toLowerCase(Locale.ROOT));
            return fallback;
        }
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw == null ? fallback : Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
