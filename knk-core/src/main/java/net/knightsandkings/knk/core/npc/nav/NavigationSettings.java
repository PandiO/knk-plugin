package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Per-NPC navigation settings with layered overrides (implementation plan Phase 1.2; BEHAVIOR_MODEL §15.3): a value
 * comes from the most specific layer that sets it, in the order code default → global → world → region → template →
 * assignment. Region overrides are matched by WorldGuard region id (domains are regions); with several regions the
 * innermost one that sets the value wins. Every resolved value says which layer and scope it came from, so an admin
 * can see why an NPC behaves as it does. Immutable once built; safe to share across threads.
 */
public final class NavigationSettings {

    /** Where a resolved value came from, lowest precedence first. */
    public enum Layer { DEFAULT, GLOBAL, WORLD, REGION, TEMPLATE, ASSIGNMENT }

    /** One setting: its config name, code default and parser (which throws {@link IllegalArgumentException}). */
    public record Key<T>(String name, T defaultValue, Function<String, T> parser, String description) {
        public Key {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(parser, "parser");
        }

        T parse(Object raw) {
            if (raw == null) {
                throw new IllegalArgumentException("no value");
            }
            return parser.apply(String.valueOf(raw).trim());
        }
    }

    /** Citizens' A* search for local legs: {@code citizens} (main thread) or {@code citizens_async}. */
    public static final Key<String> PATHFINDER = new Key<>("pathfinder", "citizens",
        oneOf("citizens", "citizens_async"), "engine path search for local legs and recovery");
    /** Blocks of the route handed to the mover at once (NAV-14, NAV-15). */
    public static final Key<Double> STRETCH_BLOCKS = new Key<>("stretch-blocks", 32.0,
        decimal(8, 128), "blocks of the route handed to the mover at once");
    /** Ticks between rechecks of the way ahead against the current rules (NAV-02); 0 = off. */
    public static final Key<Integer> RECHECK_TICKS = new Key<>("recheck-ticks", 40,
        integer(0, 1200), "ticks between rule rechecks of the way ahead (0 = off)");
    /** Ticks without movement before the engine reports stuck (Citizens stationary ticks). */
    public static final Key<Integer> STATIONARY_TICKS = new Key<>("stationary-ticks", 60,
        integer(10, 1200), "ticks without movement before the engine reports stuck");
    /** Ticks without progress along the route before K&K's watchdog calls the NPC stuck (NAV-17). */
    public static final Key<Integer> NO_PROGRESS_TICKS = new Key<>("no-progress-ticks", 60,
        integer(20, 1200), "ticks without progress along the route before the watchdog steps in");
    /** Whether NPCs push and block each other. */
    public static final Key<Boolean> COLLIDABLE = new Key<>("collidable", false, bool(),
        "whether NPCs push and block each other");
    /**
     * Outside player-loaded areas (NAV-12, developer decision 2026-11-12): {@code load} keeps the chunks ahead
     * loaded so the NPC really walks (the travel bubble, capped), {@code simulate} travels without an entity.
     */
    public static final Key<String> CHUNK_LOADING = new Key<>("chunk-loading", "load", oneOf("load", "simulate"),
        "outside loaded areas: load chunks and walk, or simulate travel");

    /** Every per-NPC setting, in display order. */
    public static final List<Key<?>> KEYS = List.of(PATHFINDER, STRETCH_BLOCKS, RECHECK_TICKS, STATIONARY_TICKS,
        NO_PROGRESS_TICKS, COLLIDABLE, CHUNK_LOADING);

    /** A resolved value and its origin ({@code scope}: the world, region or template id; null for default/global). */
    public record Resolved<T>(T value, Layer layer, String scope) {
        @Override
        public String toString() {
            return value + " (" + layer.name().toLowerCase(Locale.ROOT) + (scope == null ? "" : " " + scope) + ")";
        }
    }

    /**
     * Who is asking: the NPC's world, the regions it is in (outermost first, innermost last), its template and the
     * assignment's own overrides (any may be null or empty).
     */
    public record Scope(String world, List<String> regions, String template, Overrides assignment) {
        public Scope {
            regions = regions == null ? List.of() : List.copyOf(regions);
            assignment = assignment == null ? Overrides.EMPTY : assignment;
        }

        public static Scope of(String world) {
            return new Scope(world, List.of(), null, null);
        }
    }

    /** A sparse, parsed set of values for one scope. Immutable. */
    public static final class Overrides {
        public static final Overrides EMPTY = new Overrides(Map.of());

        private final Map<Key<?>, Object> values;

        private Overrides(Map<Key<?>, Object> values) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /**
         * Parses raw config values (key name → value). Unknown keys and bad values are skipped and described in
         * {@code rejected} (never thrown), so one typo doesn't stop the plugin.
         */
        public static Overrides parse(Map<String, ?> raw, String where, List<String> rejected) {
            if (raw == null || raw.isEmpty()) {
                return EMPTY;
            }
            Map<Key<?>, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : raw.entrySet()) {
                Key<?> key = byName(entry.getKey());
                if (key == null) {
                    rejected.add(where + "." + entry.getKey() + ": unknown setting");
                    continue;
                }
                try {
                    out.put(key, key.parse(entry.getValue()));
                } catch (IllegalArgumentException e) {
                    rejected.add(where + "." + entry.getKey() + ": " + e.getMessage());
                }
            }
            return new Overrides(out);
        }

        /** For code-defined layers (templates, assignments) without parsing. */
        public static <T> Overrides of(Key<T> key, T value) {
            return new Overrides(Map.of(key, value));
        }

        public <T> Overrides with(Key<T> key, T value) {
            Map<Key<?>, Object> copy = new LinkedHashMap<>(values);
            copy.put(key, value);
            return new Overrides(copy);
        }

        @SuppressWarnings("unchecked")
        <T> T get(Key<T> key) {
            return (T) values.get(key);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        public Set<Key<?>> keys() {
            return values.keySet();
        }
    }

    private final Overrides global;
    private final Map<String, Overrides> worlds;
    private final Map<String, Overrides> regions;
    private final Map<String, Overrides> templates;

    private NavigationSettings(Overrides global, Map<String, Overrides> worlds, Map<String, Overrides> regions,
                               Map<String, Overrides> templates) {
        this.global = global;
        this.worlds = Map.copyOf(worlds);
        this.regions = Map.copyOf(regions);
        this.templates = Map.copyOf(templates);
    }

    /** Code defaults only. */
    public static NavigationSettings defaults() {
        return new NavigationSettings(Overrides.EMPTY, Map.of(), Map.of(), Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The value of {@code key} for {@code scope}, with where it came from. */
    public <T> Resolved<T> resolve(Key<T> key, Scope scope) {
        T value = scope.assignment().get(key);
        if (value != null) {
            return new Resolved<>(value, Layer.ASSIGNMENT, null);
        }
        if (scope.template() != null) {
            Overrides template = templates.get(norm(scope.template()));
            if (template != null && (value = template.get(key)) != null) {
                return new Resolved<>(value, Layer.TEMPLATE, scope.template());
            }
        }
        List<String> regionIds = scope.regions();
        for (int i = regionIds.size() - 1; i >= 0; i--) {
            Overrides region = regions.get(norm(regionIds.get(i)));
            if (region != null && (value = region.get(key)) != null) {
                return new Resolved<>(value, Layer.REGION, regionIds.get(i));
            }
        }
        if (scope.world() != null) {
            Overrides world = worlds.get(norm(scope.world()));
            if (world != null && (value = world.get(key)) != null) {
                return new Resolved<>(value, Layer.WORLD, scope.world());
            }
        }
        if ((value = global.get(key)) != null) {
            return new Resolved<>(value, Layer.GLOBAL, null);
        }
        return new Resolved<>(key.defaultValue(), Layer.DEFAULT, null);
    }

    /** The value only. */
    public <T> T value(Key<T> key, Scope scope) {
        return resolve(key, scope).value();
    }

    /** Every setting for {@code scope} with its origin, in {@link #KEYS} order (for status output). */
    public Map<Key<?>, Resolved<?>> describe(Scope scope) {
        Map<Key<?>, Resolved<?>> out = new LinkedHashMap<>();
        for (Key<?> key : KEYS) {
            out.put(key, resolve(key, scope));
        }
        return out;
    }

    /** How many override scopes each layer has (for the startup line). */
    public Map<Layer, Integer> scopeCounts() {
        Map<Layer, Integer> out = new EnumMap<>(Layer.class);
        out.put(Layer.GLOBAL, global.isEmpty() ? 0 : 1);
        out.put(Layer.WORLD, worlds.size());
        out.put(Layer.REGION, regions.size());
        out.put(Layer.TEMPLATE, templates.size());
        return out;
    }

    public static Key<?> byName(String name) {
        for (Key<?> key : KEYS) {
            if (key.name().equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    private static String norm(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    /** Collects the layers; ids (worlds, regions, templates) match case-insensitively. */
    public static final class Builder {
        private Overrides global = Overrides.EMPTY;
        private final Map<String, Overrides> worlds = new HashMap<>();
        private final Map<String, Overrides> regions = new HashMap<>();
        private final Map<String, Overrides> templates = new HashMap<>();

        public Builder global(Overrides overrides) {
            this.global = overrides;
            return this;
        }

        public Builder world(String world, Overrides overrides) {
            put(worlds, world, overrides);
            return this;
        }

        public Builder region(String regionId, Overrides overrides) {
            put(regions, regionId, overrides);
            return this;
        }

        public Builder template(String templateId, Overrides overrides) {
            put(templates, templateId, overrides);
            return this;
        }

        public NavigationSettings build() {
            return new NavigationSettings(global, worlds, regions, templates);
        }

        private static void put(Map<String, Overrides> into, String id, Overrides overrides) {
            if (id != null && overrides != null && !overrides.isEmpty()) {
                into.put(norm(id), overrides);
            }
        }
    }

    // ---- parsers ----

    static Function<String, String> oneOf(String... allowed) {
        List<String> options = List.of(allowed);
        return raw -> {
            String value = raw.toLowerCase(Locale.ROOT);
            if (!options.contains(value)) {
                throw new IllegalArgumentException("'" + raw + "' is not one of " + options);
            }
            return value;
        };
    }

    static Function<String, Integer> integer(int min, int max) {
        return raw -> {
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + raw + "' is not a whole number");
            }
            if (value < min || value > max) {
                throw new IllegalArgumentException(value + " is outside " + min + ".." + max);
            }
            return value;
        };
    }

    static Function<String, Double> decimal(double min, double max) {
        return raw -> {
            double value;
            try {
                value = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + raw + "' is not a number");
            }
            if (!(value >= min && value <= max)) {
                throw new IllegalArgumentException(raw + " is outside " + min + ".." + max);
            }
            return value;
        };
    }

    static Function<String, Boolean> bool() {
        return raw -> switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> throw new IllegalArgumentException("'" + raw + "' is not true or false");
        };
    }

    /** All rejected entries, for a startup warning; a convenience for callers collecting several layers. */
    public static List<String> newRejectedList() {
        return new ArrayList<>();
    }
}
