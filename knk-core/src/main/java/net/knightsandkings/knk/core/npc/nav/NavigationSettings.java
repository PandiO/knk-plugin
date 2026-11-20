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
    public static final Key<Integer> STATIONARY_TICKS = new Key<>("stationary-ticks", 40,
        integer(10, 1200), "ticks in one block before the engine reports stuck; the first recovery step starts then");
    /** Ticks without progress along the route before K&K's watchdog calls the NPC stuck (NAV-17). */
    public static final Key<Integer> NO_PROGRESS_TICKS = new Key<>("no-progress-ticks", 40,
        integer(20, 1200), "ticks without progress along the route before the watchdog steps in");
    /** Whether NPCs push and block each other. */
    public static final Key<Boolean> COLLIDABLE = new Key<>("collidable", false, bool(),
        "whether NPCs push and block each other");
    /**
     * With {@code collidable} on: whether players' bodies push NPCs too (implementation plan 4c.4 item 7, link M7). Off:
     * every player is exempt from every NPC's collision, so players never move an NPC (push resistance first,
     * developer 2026-11-16); on: players bump NPCs, a pushed NPC walks back and a player who keeps pushing is exempted
     * for a while ({@code push-exempt-*}).
     */
    public static final Key<Boolean> PLAYERS_PUSH_NPCS = new Key<>("players-push-npcs", false, bool(),
        "with collidable on: whether players' bodies push NPCs too (off: players never move an NPC)");
    /** With {@code collidable} on: a pushed NPC walks back to its line or post (4c.4 item 7). */
    public static final Key<Boolean> PUSH_RETURN = new Key<>("push-return", true, bool(),
        "with collidable on: a pushed or knocked-back NPC walks back to its line or post");
    /** Pushes by one player within {@code push-exempt-window-ticks} before that player stops colliding with the NPC. */
    public static final Key<Integer> PUSH_EXEMPT_AFTER = new Key<>("push-exempt-after", 3, integer(1, 20),
        "pushes by one player within push-exempt-window-ticks before that player stops colliding with the NPC");
    /** The window in which {@code push-exempt-after} pushes by one player count. */
    public static final Key<Integer> PUSH_EXEMPT_WINDOW_TICKS = new Key<>("push-exempt-window-ticks", 200,
        integer(20, 6000), "ticks in which push-exempt-after pushes by one player count");
    /** How long a player who kept pushing an NPC stops colliding with it. */
    public static final Key<Integer> PUSH_EXEMPT_TICKS = new Key<>("push-exempt-ticks", 1200, integer(20, 72000),
        "ticks a player who kept pushing an NPC stops colliding with it");
    /**
     * Ticks a pushed NPC may take to walk back before it teleports home, only where no player sees it leave or arrive
     * (the last step, 4c.4 item 7); 0 = never.
     */
    public static final Key<Integer> PUSH_TELEPORT_TICKS = new Key<>("push-teleport-ticks", 600, integer(0, 12000),
        "ticks a pushed NPC may take to walk back before it teleports home out of every player's sight (0 = never)");
    /**
     * Outside player-loaded areas (NAV-12, developer decision 2026-11-12): {@code load} keeps the chunks ahead
     * loaded so the NPC really walks (the travel bubble, capped), {@code simulate} travels without an entity.
     */
    public static final Key<String> CHUNK_LOADING = new Key<>("chunk-loading", "load", oneOf("load", "simulate"),
        "outside loaded areas: load chunks and walk, or simulate travel");

    /** Blocks between the hops of an off-road or recovery leg, each walked with the engine's path search (NAV-01). */
    public static final Key<Double> HOP_BLOCKS = new Key<>("hop-blocks", 16.0,
        decimal(4, 48), "blocks between the hops of an off-road or recovery leg");
    /**
     * Recovery steps of a stuck NPC before its route is planned again (DESIGN §6; implementation plan 4b.2 item 7):
     * 1 back-off, 2 side-step, 3 local leg ahead.
     */
    public static final Key<Integer> MAX_RETRIES = new Key<>("max-retries", 3,
        integer(0, 10), "recovery steps of a stuck NPC before its route is planned again: 1 back-off, 2 side-step, "
            + "3 local leg ahead");
    /** Ticks between two recovery steps of a stuck NPC (implementation plan 4b.2 item 7.1, decided 2026-11-15). */
    public static final Key<Integer> RECOVERY_WAIT_TICKS = new Key<>("recovery-wait-ticks", 20,
        integer(0, 200), "ticks between two recovery steps of a stuck NPC (back-off, side-step, local leg, replan)");
    /**
     * Blocks an NPC keeps between itself and a wall on the inside of a turn of its route (implementation plan 4b.2
     * item 1, decided 2026-11-15); 0 = off.
     */
    public static final Key<Double> CORNER_CLEARANCE = new Key<>("corner-clearance", 1.0,
        decimal(0, 2), "blocks an NPC keeps from a wall on the inside of a turn, as far as the road allows (0 = off)");
    /**
     * How close an NPC comes to a route waypoint before it heads for the next one (implementation plan 4b.2 item 2,
     * decided 2026-11-15); Citizens' own default is 1.
     */
    public static final Key<Double> WAYPOINT_MARGIN = new Key<>("waypoint-margin", 0.5,
        decimal(0.2, 1.5), "how close an NPC comes to a route waypoint before it heads for the next one");
    /**
     * Corridor steering (implementation plan 4c.3 decision 1, option A, link M2; BEHAVIOR_MODEL NAV-24): each stretch
     * of the route line is re-placed in its walkable corridor before the engine walks it, away from walls, straight
     * where the road allows and through the middle of doorways. Off by default until the developer has tested it.
     */
    public static final Key<Boolean> CORRIDOR_STEERING = new Key<>("corridor-steering", false, bool(),
        "shape each stretch of the route line in its corridor: off walls, straightened, through doorways' middles");
    /** Blocks either side of the route line the corridor looks at (plan 4c.6 question 3, decided 2026-11-15: 4). */
    public static final Key<Double> CORRIDOR_HALF_WIDTH = new Key<>("corridor-half-width", 4.0,
        decimal(1, 8), "blocks either side of the route line that corridor steering looks at");
    /**
     * Blocks corridor steering keeps from the body's centre to a wall, post or drop where the road allows (plan 4c.6
     * question 4, decided 2026-11-15: 0.8; never less than 0.35, so one-block doors stay open).
     */
    public static final Key<Double> WALL_CLEARANCE = new Key<>("wall-clearance", 0.8,
        decimal(0.35, 2), "blocks from the body's centre to a wall, post or drop that corridor steering keeps where "
            + "the road allows (at least 0.35)");
    /**
     * Clearance-aware local legs (implementation plan 4c.3 decision 8, add-on D, link M6; BEHAVIOR_MODEL NAV-24): short
     * trips, a follower's own leg back and the recovery ladder's local leg are searched on K&K's walk grid with the
     * corridor's clearance (the body's width as the hard minimum, {@code wall-clearance} as a cost), smoothed, and
     * walked as a waypoint list; the engine's own search is the fallback. Off by default until the developer has
     * tested it.
     */
    public static final Key<Boolean> LOCAL_LEGS_CLEARANCE = new Key<>("local-legs-clearance", false, bool(),
        "search local legs on K&K's walk grid with the corridor's clearance, the engine's search as the fallback");
    /** Ticks an NPC waits before asking again when the rules allow no route or the way ahead is not loaded (NAV-03). */
    public static final Key<Integer> NO_ROUTE_WAIT_TICKS = new Key<>("no-route-wait-ticks", 100,
        integer(20, 6000), "ticks between asks while no route is allowed or the way ahead is not loaded");
    /**
     * With an end too far from a road (or no road network): the longest trip, in blocks, walked as off-road hops
     * only. Further, the order fails instead of crossing terrain in a straight line.
     */
    public static final Key<Double> WALK_ONLY_BLOCKS = new Key<>("walk-only-blocks", 64.0,
        decimal(0, 256), "longest trip without a road, walked as off-road hops");

    /**
     * A point destination's road goal by walk reachability (link G1, PandiO/knk-workspace#814; the off-road chain's
     * rule of {@code navigation.road-goal-reachable}, F2/F3, #780): when the road's end is off the destination, the
     * route may end at another road point instead: today's goal, the nearest point of each road edge within
     * {@code navigation.max-snap-distance} of the destination and the road's nodes onward, at most 12, each with a road
     * route and one walk search to the destination; the least road plus {@code road-goal-walk-weight} times walk wins.
     * None reached: today's goal and its walk (link C1). Off by default until the developer has tested it.
     */
    public static final Key<Boolean> ROAD_GOAL_REACHABLE = new Key<>("road-goal-reachable", false, bool(),
        "choose the road goal of a point destination among the nearby road points by road plus weighted walk length");
    /**
     * With {@code road-goal-reachable}: a candidate's score is its road length plus this many times its walk length
     * (F3's rule; players use {@code navigation.road-goal-walk-weight}, 4.0). 1 = road and walk count alike.
     */
    public static final Key<Double> ROAD_GOAL_WALK_WEIGHT = new Key<>("road-goal-walk-weight", 4.0,
        decimal(1, 100), "with road-goal-reachable: how many blocks of road one block walked counts as");

    /**
     * What happens to an NPC that entered a region its current rules deny (the trespass guard; it is always logged
     * and counted): {@code true} also stops it and plans its route again. For dev servers.
     */
    public static final Key<Boolean> STOP_ON_TRESPASS = new Key<>("stop-on-trespass", false, bool(),
        "stop and replan an NPC that entered a region its rules deny (dev servers)");

    // ---- groups (implementation plan Phase 4, decision Q4): resolved once per group, for its leader ----

    /** A group's formation: {@code column}, {@code ranks} (rows behind the leader), {@code cluster}, {@code escort-ring}. */
    public static final Key<String> GROUP_FORMATION = new Key<>("group-formation", "column",
        oneOf("column", "ranks", "cluster", "escort-ring"), "a group's formation: column, ranks, cluster or escort-ring");
    /** Blocks between the rows of a formation and between NPCs in a column (decided 2026-11-15: 1.5, was 2.0). */
    public static final Key<Double> GROUP_SPACING = new Key<>("group-spacing", 1.5,
        decimal(1, 8), "blocks between the rows of a formation and between NPCs in a column");
    /**
     * Blocks between neighbours in a row of a formation (NAV-22, implementation plan 4b.3 item 1, decided
     * 2026-11-15); 1 = shoulder to shoulder.
     */
    public static final Key<Double> GROUP_LATERAL_SPACING = new Key<>("group-lateral-spacing", 1.0,
        decimal(1, 8), "blocks between neighbours in a row of a formation (1 = shoulder to shoulder)");
    /** NPCs per row in the {@code ranks} formation (2 = pairs). */
    public static final Key<Integer> GROUP_RANKS_WIDTH = new Key<>("group-ranks-width", 2,
        integer(2, 8), "NPCs per row in the ranks formation (2 = pairs)");
    /** A follower further than this from its lane (or stuck) plans its own leg back; never the whole route. */
    public static final Key<Double> GROUP_REJOIN_BLOCKS = new Key<>("group-rejoin-blocks", 4.0,
        decimal(2, 16), "blocks off its lane before a follower plans its own leg back");
    /** The leader waits when a follower is this far behind its slot, until the group has closed up; 0 = never. */
    public static final Key<Double> GROUP_REGROUP_BLOCKS = new Key<>("group-regroup-blocks", 24.0,
        decimal(0, 256), "blocks a follower may fall behind its slot before the leader waits (0 = never)");
    /**
     * Blocks a narrower width (still 2 abreast or more) or a wider one must last along the path before a group takes
     * it (implementation plan 4b.3 item 5, decided 2026-11-15); a column at a gate or a stair is taken at once.
     */
    public static final Key<Integer> GROUP_WIDTH_HYSTERESIS_BLOCKS = new Key<>("group-width-hysteresis-blocks", 3,
        integer(0, 16), "blocks a narrower or wider formation width must last before a group changes to it");
    /**
     * How a group keeps its formation (implementation plan 4c.3 decision 2, link M3; BEHAVIOR_MODEL NAV-25):
     * {@code levels} (today: fixed widths, a column at once where 2 abreast does not fit) or {@code elastic} (the
     * width fitted from the free width across the path, squeezed to {@code group-lateral-spacing-min} and
     * {@code group-spacing-min} before it narrows, decided {@code group-lookahead-blocks} ahead, the same hysteresis for
     * every change with lanes merging for short tight places, the column only as a last resort, stairs like any road,
     * lanes by the access rules, slots re-assigned by place). Default {@code levels} until the developer has tested it.
     */
    public static final Key<String> GROUP_FORMATION_KEEPING = new Key<>("group-formation-keeping", "levels",
        oneOf("levels", "elastic"), "how a group keeps its formation: levels (fixed widths) or elastic (squeeze, "
            + "look ahead, fold last)");
    /**
     * Blocks between neighbours in a row that an elastic formation squeezes down to before it narrows (plan 4c.6
     * question 5, decided 2026-11-15: 0.6, the body's width, so shoulders touch).
     */
    public static final Key<Double> GROUP_LATERAL_SPACING_MIN = new Key<>("group-lateral-spacing-min", 0.6,
        decimal(0.5, 8), "blocks between neighbours in a row an elastic formation squeezes to before it narrows");
    /**
     * Blocks between the rows that an elastic formation closes up to while it squeezes (plan 4c.6 question 5, decided
     * 2026-11-15: 1.0, the body's depth plus 0.4).
     */
    public static final Key<Double> GROUP_SPACING_MIN = new Key<>("group-spacing-min", 1.0,
        decimal(0.6, 8), "blocks between the rows an elastic formation closes up to while it squeezes");
    /**
     * Blocks an elastic formation looks ahead past its own depth when it decides its width (plan 4c.6 question 8,
     * decided 2026-11-15: the formation's depth plus 4).
     */
    public static final Key<Integer> GROUP_LOOKAHEAD_BLOCKS = new Key<>("group-lookahead-blocks", 4,
        integer(0, 32), "blocks an elastic formation looks ahead of its leader past its own depth");
    /**
     * Followers still on their way when the leader has arrived finish their trip to the goal first, then rejoin the
     * group (NAV-23, implementation plan 4b.3 items 8 to 11, decided 2026-11-15); false: they follow the leader.
     */
    public static final Key<Boolean> GROUP_FOLLOWERS_FINISH_TRIP = new Key<>("group-followers-finish-trip", false,
        bool(), "followers still on their way when the leader arrived finish their trip first (false: follow the leader)");
    /**
     * Plaza lines (implementation plan 4c.3 decision 6, link M5; BEHAVIOR_MODEL NAV-27): where a route passes a plaza
     * junction (a designed plaza, or a junction with open floor round it wider than {@code corridor-half-width}), the
     * corridor widens to the plaza and the line is string-pulled across it from where the route enters to where it
     * leaves, with {@code wall-clearance} round wells and stalls and never into a region the line does not pass. Off by
     * default until the developer has tested it.
     */
    public static final Key<Boolean> PLAZA_LINES = new Key<>("plaza-lines", false, bool(),
        "string-pull the line across plazas from where the route enters to where it leaves, not through the middle");
    /** The largest plaza radius, in blocks, the corridor widens to (plan 4c.3 decision 6: 16). */
    public static final Key<Integer> PLAZA_CORRIDOR_BLOCKS = new Key<>("plaza-corridor-blocks", 16,
        integer(4, 32), "the largest plaza radius in blocks that plaza lines widen the corridor to");
    /**
     * Crowd awareness (implementation plan 4c.3 decision 3, link M4; BEHAVIOR_MODEL NAV-26): an occupancy hash of the
     * navigating NPCs' feet cells every 5 ticks; narrow places (free width under 2 blocks) taken in turns, one direction
     * at a time, an NPC already in one first, then first come; NPCs that meet head-on in one: the second to enter steps
     * aside; waiting behind someone, in a queue or yielding is counted as waiting, not as a stuck spell; recovery steps
     * never end on an occupied cell. Off by default until the developer has tested it.
     */
    public static final Key<Boolean> CROWD_AWARENESS = new Key<>("crowd-awareness", false, bool(),
        "take narrow places in turns (one direction at a time, first come), step aside for oncoming NPCs, and count "
            + "waiting behind others as waiting, not stuck");
    /** Walking speed, as a factor, of a follower that is behind its slot; 1 = no catching up. */
    public static final Key<Double> GROUP_CATCH_UP_SPEED = new Key<>("group-catch-up-speed", 1.3,
        decimal(1, 2), "speed factor of a follower that is behind its slot (1 = off)");

    /** Every per-NPC setting, in display order. */
    public static final List<Key<?>> KEYS = List.of(PATHFINDER, STRETCH_BLOCKS, HOP_BLOCKS, RECHECK_TICKS,
        STATIONARY_TICKS, NO_PROGRESS_TICKS, MAX_RETRIES, RECOVERY_WAIT_TICKS, CORNER_CLEARANCE, WAYPOINT_MARGIN,
        CORRIDOR_STEERING, CORRIDOR_HALF_WIDTH, WALL_CLEARANCE, LOCAL_LEGS_CLEARANCE, PLAZA_LINES,
        PLAZA_CORRIDOR_BLOCKS, CROWD_AWARENESS, NO_ROUTE_WAIT_TICKS, WALK_ONLY_BLOCKS, ROAD_GOAL_REACHABLE,
        ROAD_GOAL_WALK_WEIGHT, STOP_ON_TRESPASS,
        COLLIDABLE, PLAYERS_PUSH_NPCS, PUSH_RETURN, PUSH_EXEMPT_AFTER, PUSH_EXEMPT_WINDOW_TICKS, PUSH_EXEMPT_TICKS,
        PUSH_TELEPORT_TICKS, CHUNK_LOADING, GROUP_FORMATION, GROUP_SPACING, GROUP_LATERAL_SPACING, GROUP_RANKS_WIDTH,
        GROUP_WIDTH_HYSTERESIS_BLOCKS, GROUP_FORMATION_KEEPING, GROUP_LATERAL_SPACING_MIN, GROUP_SPACING_MIN,
        GROUP_LOOKAHEAD_BLOCKS, GROUP_FOLLOWERS_FINISH_TRIP, GROUP_REJOIN_BLOCKS, GROUP_REGROUP_BLOCKS,
        GROUP_CATCH_UP_SPEED);

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
