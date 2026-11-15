package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * What the NPC movement scenarios measure and how they report it (implementation plan 4c.4 item 1.2, link M1,
 * PandiO/knk-workspace#621). In M1 they run in <b>report mode</b>: each logs one {@value #TAG} line per phase with its
 * numbers and passes; links M2 to M7 turn the numbers they fix into checks. Each scenario runs with
 * {@code npc.navigation.collidable} off (its plain name) and on (the name with {@value #COLLISION_SUFFIX}, decision
 * 3.6). Link M2 turned the singles' numbers of {@code npc-stairhouse-singles} and {@code npc-jagged-straight} into
 * checks, run with {@code corridor-steering} on ({@link #singlesFailures}).
 * <p>
 * A line reads {@code KNK-NPC-REPORT <scenario> | <phase> | key value | key value ...}; CI copies the lines into the
 * {@code paper-boot} job's summary.
 */
public final class NpcMovementReport {

    /** The log tag of a report line. */
    public static final String TAG = "KNK-NPC-REPORT";
    /** The name suffix of a scenario's collision-on run. */
    public static final String COLLISION_SUFFIX = "-collision-on";

    private final ScenarioScene scene;
    private final String name;
    private final List<String> parts = new ArrayList<>();
    /** Event and reason totals at the start of the phase ({@link #phaseStarts}); a scenario's NPCs share one metrics. */
    private final Map<Event, Long> base = new HashMap<>();
    private final Map<String, Long> baseReasons = new HashMap<>();
    private long baseNearWall;
    private long phaseTick;

    NpcMovementReport(ScenarioScene scene, String name) {
        this.scene = scene;
        this.name = name;
    }

    /**
     * The settings of a run: the code defaults with {@code collidable} on or off, and {@code ranks} 3 wide (the plan's
     * "20 in ranks of 3", 4c.1 item 4.2; the code default is 2).
     */
    static NavigationSettings settings(boolean collisionOn) {
        return settings(collisionOn, false);
    }

    /**
     * As {@link #settings(boolean)}, with {@code corridor-steering} on or off (link M2; its other keys at their decided
     * defaults).
     */
    static NavigationSettings settings(boolean collisionOn, boolean corridorSteering) {
        return settings(collisionOn, corridorSteering, false);
    }

    /** As {@link #settings(boolean, boolean)}, with {@code local-legs-clearance} on or off (link M6). */
    static NavigationSettings settings(boolean collisionOn, boolean corridorSteering, boolean localLegsClearance) {
        return settings(collisionOn, corridorSteering, localLegsClearance, false);
    }

    /**
     * As {@link #settings(boolean, boolean, boolean)}, with {@code group-formation-keeping} {@code elastic} or
     * {@code levels} (link M3).
     */
    static NavigationSettings settings(boolean collisionOn, boolean corridorSteering, boolean localLegsClearance,
                                       boolean elastic) {
        return NavigationSettings.builder()
            .global(NavigationSettings.Overrides.of(NavigationSettings.COLLIDABLE, collisionOn)
                .with(NavigationSettings.GROUP_RANKS_WIDTH, 3)
                .with(NavigationSettings.CORRIDOR_STEERING, corridorSteering)
                .with(NavigationSettings.LOCAL_LEGS_CLEARANCE, localLegsClearance)
                .with(NavigationSettings.GROUP_FORMATION_KEEPING, elastic ? "elastic" : "levels")).build();
    }

    /** Free width under which 2 abreast don't fit even squeezed (2 × 0.3 + 2 × 0.35 at 0.6): a 1-wide place. */
    static final double ONE_WIDE = 0.6 + 0.7;

    /**
     * The 1-wide places on a road line (plan 4c.4 item 3.3): runs of points, every half block along the line, where
     * the free width across it (M1's {@code FreeWidth}, the trace's measure) is under {@link #ONE_WIDE}.
     */
    static int oneWidePlaces(net.knightsandkings.knk.core.npc.nav.NavWorld world, List<NavPoint> floorLine) {
        List<NavPoint> points = net.knightsandkings.knk.core.npc.nav.HopPlanner.resampleKeepingCorners(floorLine, 0.5);
        int places = 0;
        boolean in = false;
        for (int i = 0; i < points.size(); i++) {
            NavPoint before = points.get(Math.max(0, i - 1));
            NavPoint after = points.get(Math.min(points.size() - 1, i + 1));
            NavPoint feet = net.knightsandkings.knk.core.npc.nav.HopPlanner.feet(points.get(i));
            boolean narrow = net.knightsandkings.knk.core.npc.nav.corridor.FreeWidth.at(world, feet,
                after.x() - before.x(), after.z() - before.z()).total() < ONE_WIDE;
            places += narrow && !in ? 1 : 0;
            in = narrow;
        }
        return places;
    }

    /**
     * The group checks of plan 4c.4 item 3.3 (link M3, with {@code group-formation-keeping: elastic}): the leader
     * arrived, at most {@code maxFolds} folds, no stuck spell left open or not freed, nobody stuck near a wall (no
     * clipped wall corner). Null when they hold, else what failed.
     */
    String groupFailures(ScenarioNpcs npcs, net.knightsandkings.knk.core.npc.nav.NavigationResult result, int maxFolds) {
        List<String> failed = new ArrayList<>();
        if (!(result instanceof net.knightsandkings.knk.core.npc.nav.NavigationResult.Arrived)) {
            failed.add("the leader " + (result == null ? "did not arrive in time" : "ended with " + result));
        }
        long folds = count(npcs, Event.GROUP_FOLD);
        if (folds > maxFolds) {
            failed.add(folds + " folds (at most " + maxFolds + ", the 1-wide places)");
        }
        NpcNavigationMetrics m = npcs.metrics();
        if (m.spells().open() != 0 || count(npcs, Event.NOT_FREED) != 0) {
            failed.add("stuck spells open " + m.spells().open() + ", not freed " + count(npcs, Event.NOT_FREED));
        }
        long nearWall = m.spells().started(true) - baseNearWall;
        if (nearWall != 0) {
            failed.add(nearWall + " stuck spells near a wall");
        }
        return failed.isEmpty() ? null : String.join("; ", failed);
    }

    /** The local legs of this phase (link M6): found by K&K, walked with the engine's search, no path either way. */
    NpcMovementReport localLegs(ScenarioNpcs npcs) {
        return add("local legs", "k&k %d, engine %d, none %d", count(npcs, Event.LOCAL_LEG_KNK),
            count(npcs, Event.LOCAL_LEG_ENGINE), count(npcs, Event.LOCAL_LEG_NONE));
    }

    /**
     * The check of plan 4c.4 item 6.3 on the stair-house scene (link M6, with {@code local-legs-clearance} on): no local
     * leg ends without a path (no {@code none}, no {@code recovery_no_path}). Null when it holds, else what failed.
     */
    String localLegFailures(ScenarioNpcs npcs) {
        long none = count(npcs, Event.LOCAL_LEG_NONE);
        long noPath = count(npcs, Event.RECOVERY_NO_PATH);
        return none == 0 && noPath == 0 ? null : "local legs without a path " + none + ", recovery no path " + noPath;
    }

    /** The longest a single may stand still (plan 4c.4 item 2.3: nobody still longer than 3 s). */
    static final int MAX_STILL_TICKS = 3 * 20;
    /**
     * Corridor steering's own cost per NPC per tick that the scenarios allow: the 0.03 ms budget of plan 4c.1 item 7.
     * Its share is checked rather than the whole K&K figure, which in these short runs includes Citizens port calls
     * and start-up (M1's notes). Warm, the share is about 0.005 ms here; the first run in a server pays the JIT.
     */
    static final double MAX_CORRIDOR_MS = 0.03;

    /** Corridor steering's counts and cost (link M2): stretches shaped, handed out again, ms per NPC per tick. */
    NpcMovementReport corridor(ScenarioNpcs npcs) {
        return add("corridor", "shaped %d, shared %d, %.4f ms per NPC per tick", npcs.service().corridors().shaped(),
            npcs.service().corridors().shared(), npcs.corridorMsPerNpcTick());
    }

    /**
     * The singles' checks of plan 4c.4 item 2.3 (link M2, with {@code corridor-steering} on): all arrived, 0 stuck
     * spells, nobody still longer than 3 s, no fall, corridor steering really shaped and within its cost. Null when
     * they hold, else what failed.
     */
    String singlesFailures(ScenarioNpcs npcs, SingleTrips trips) {
        List<String> failed = new ArrayList<>();
        if (!trips.allEnded() || trips.arrived() != trips.movers().size()) {
            failed.add("arrived " + trips.arrivals() + " (" + trips.others() + ")");
        }
        long spells = count(npcs, Event.STUCK_ENGINE) + count(npcs, Event.STUCK_NO_PROGRESS);
        if (spells != 0) {
            failed.add(spells + " stuck spells");
        }
        if (npcs.longestStillTicks() > MAX_STILL_TICKS) {
            failed.add(String.format(Locale.ROOT, "still for %.1f s (%s)", npcs.longestStillTicks() / 20.0,
                npcs.longestStillWho()));
        }
        if (count(npcs, Event.FELL) != 0) {
            failed.add(count(npcs, Event.FELL) + " falls");
        }
        if (npcs.service().corridors().shaped() == 0) {
            failed.add("corridor steering shaped no stretch");
        }
        if (!(npcs.corridorMsPerNpcTick() <= MAX_CORRIDOR_MS)) {
            failed.add(String.format(Locale.ROOT, "corridor steering %.4f ms per NPC per tick (at most %.2f)",
                npcs.corridorMsPerNpcTick(), MAX_CORRIDOR_MS));
        }
        return failed.isEmpty() ? null : String.join("; ", failed);
    }

    /** The scenario's name for a run. */
    static String name(String base, boolean collisionOn) {
        return collisionOn ? base + COLLISION_SUFFIX : base;
    }

    /** Adds {@code key value} to the line being built. */
    NpcMovementReport add(String key, Object value) {
        parts.add(key + " " + value);
        return this;
    }

    /** Adds a formatted {@code key value}. */
    NpcMovementReport add(String key, String format, Object... args) {
        parts.add(key + " " + String.format(Locale.ROOT, format, args));
        return this;
    }

    /** A new phase starts: the counts of the next line are from here on. */
    NpcMovementReport phaseStarts(ScenarioNpcs npcs) {
        NpcNavigationMetrics m = npcs.metrics();
        for (Event event : Event.values()) {
            base.put(event, m.total(event));
        }
        for (Event change : NpcNavigationMetrics.WIDTH_CHANGES) {
            for (NpcNavigationMetrics.FoldReason reason : NpcNavigationMetrics.FoldReason.values()) {
                baseReasons.put(change.name() + reason.name(), m.foldReasonTotal(change, reason));
            }
        }
        baseNearWall = m.spells().started(true);
        phaseTick = npcs.ticks();
        npcs.resetLongestStill();
        return this;
    }

    /** An event's count in this phase. */
    long count(ScenarioNpcs npcs, Event event) {
        return npcs.metrics().total(event) - base.getOrDefault(event, 0L);
    }

    /** The stuck spells, falls and trespasses, the longest still single and the tick cost (every phase has them). */
    NpcMovementReport common(ScenarioNpcs npcs, NpcMovementScene where) {
        NpcNavigationMetrics m = npcs.metrics();
        long spells = count(npcs, Event.STUCK_ENGINE) + count(npcs, Event.STUCK_NO_PROGRESS);
        add("stuck spells", spells + " (near a wall " + (m.spells().started(true) - baseNearWall) + ", not freed "
            + count(npcs, Event.NOT_FREED) + ", open now " + m.spells().open() + ")");
        add("recovery no path", count(npcs, Event.RECOVERY_NO_PATH));
        localLegs(npcs);
        add("falls", count(npcs, Event.FELL));
        add("trespasses", count(npcs, Event.TRESPASS));
        add("longest still single", String.format(Locale.ROOT, "%.1f s (%s)", npcs.longestStillTicks() / 20.0,
            boxPosition(npcs.longestStillWho(), where)));
        add("K&K nav", "%.4f ms per NPC per tick", npcs.navMsPerNpcTick());
        return this;
    }

    /**
     * The group's width changes with their reasons (decision 2.8) and positions, from the run log, counted per kind:
     * {@code folds 2 (door 1, stair 1) at ...}.
     */
    NpcMovementReport widthChanges(ScenarioNpcs npcs, NpcMovementScene where) {
        NpcNavigationMetrics m = npcs.metrics();
        add("folds", count(npcs, Event.GROUP_FOLD) + reasons(m, Event.GROUP_FOLD));
        add("narrows", count(npcs, Event.GROUP_NARROW) + reasons(m, Event.GROUP_NARROW));
        add("merges", count(npcs, Event.GROUP_MERGE) + reasons(m, Event.GROUP_MERGE));
        add("reforms", count(npcs, Event.GROUP_REFORM));
        add("widens", count(npcs, Event.GROUP_WIDEN));
        List<String> changes = new ArrayList<>();
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            if (line.tick() < phaseTick) {
                continue;
            }
            String text = line.text();
            if (text.startsWith("group ") && (text.contains(" folds ") || text.contains(" narrows ")
                || text.contains(" takes up ") || text.contains(" widens ") || text.contains(" merges its lanes "))) {
                changes.add(shortChange(text, where));
            }
        }
        add("changes", changes.isEmpty() ? "-" : String.join("; ", changes));
        return this;
    }

    private String reasons(NpcNavigationMetrics m, Event change) {
        StringBuilder out = new StringBuilder();
        for (NpcNavigationMetrics.FoldReason reason : NpcNavigationMetrics.FoldReason.values()) {
            long n = m.foldReasonTotal(change, reason) - baseReasons.getOrDefault(change.name() + reason.name(), 0L);
            if (n > 0) {
                out.append(out.isEmpty() ? " (" : ", ").append(reason.label()).append(' ').append(n);
            }
        }
        return out.isEmpty() ? "" : out.append(')').toString();
    }

    private static final java.util.regex.Pattern AT_BLOCK = java.util.regex.Pattern.compile("(.*) at (-?\\d+) (-?\\d+) (-?\\d+)$");

    /** "knk-sc-5 at 30014 103 30006" (a feet block in the world) as "knk-sc-5 at 14 2 6" (box, floor y). */
    static String boxPosition(String who, NpcMovementScene where) {
        java.util.regex.Matcher m = AT_BLOCK.matcher(who);
        if (!m.matches()) {
            return who;
        }
        return m.group(1) + " at " + where.local(new NavPoint("", Integer.parseInt(m.group(2)) + 0.5,
            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)) + 0.5));
    }

    /** "folds (door) at 13 2 7" from a width line, the position in box coordinates (floor y). */
    static String shortChange(String line, NpcMovementScene where) {
        String what = line.contains(" folds ") ? "fold" : line.contains(" narrows ") ? "narrow"
            : line.contains(" merges its lanes ") ? "merge" : line.contains(" takes up ") ? "reform" : "widen";
        int open = line.lastIndexOf(" (");
        int at = line.lastIndexOf(" at ");
        // a reason is the bracket right before " at " after the change's text ("group 1 (name, n NPCs)" is not one)
        String reason = open >= 0 && at > open && line.charAt(at - 1) == ')' && open > line.indexOf(" NPCs)")
            ? line.substring(open + 1, at) : "";
        String position = "";
        if (at >= 0) {
            String[] words = line.substring(at + 4).trim().split(" ");
            if (words.length == 3) {
                try {
                    position = " at " + where.local(new NavPoint("", Integer.parseInt(words[0]) + 0.5,
                        Integer.parseInt(words[1]), Integer.parseInt(words[2]) + 0.5));
                } catch (NumberFormatException e) {
                    position = " at " + line.substring(at + 4).trim();
                }
            }
        }
        return what + (reason.isEmpty() ? "" : " " + reason) + position;
    }

    /** Writes the line to the server log and starts a new one; returns what was written. */
    String log(String phase) {
        String line = TAG + " " + name + " | " + phase + " | " + String.join(" | ", parts);
        scene.services().plugin().getLogger().info(line);
        parts.clear();
        return line;
    }

    /**
     * Each NPC's longest stand in one block inside an area while {@code counts} says it should be moving, in ticks
     * (the 4c.5 checks "no follower still in the entrance for more than 10 s").
     */
    static final class StillInArea {
        private final Map<Integer, int[]> last = new HashMap<>();
        private final Map<Integer, Integer> ticks = new HashMap<>();
        private int longest;
        private String who = "-";

        void look(NpcMover mover, NavPoint at, boolean inArea, NpcMovementScene where) {
            int[] block = at == null ? null : new int[] {at.blockX(), at.blockY(), at.blockZ()};
            int[] before = block == null ? null : last.put(mover.id(), block);
            int n = inArea && before != null && java.util.Arrays.equals(before, block)
                ? ticks.getOrDefault(mover.id(), 0) + 1 : 0;
            ticks.put(mover.id(), n);
            if (n > longest) {
                longest = n;
                who = mover.name() + " at " + where.local(at);
            }
        }

        String describe() {
            return String.format(Locale.ROOT, "%.1f s (%s)", longest / 20.0, who);
        }

        int longestTicks() {
            return longest;
        }
    }
}
