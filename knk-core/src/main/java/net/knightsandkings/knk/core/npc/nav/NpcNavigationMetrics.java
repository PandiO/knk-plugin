package net.knightsandkings.knk.core.npc.nav;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import net.knightsandkings.knk.core.metrics.LabeledHistogram;
import net.knightsandkings.knk.core.metrics.MetricsCollector;
import net.knightsandkings.knk.core.metrics.PrometheusWriter;

/**
 * NPC navigation's instruments (NAV-16; series {@code knk_npc_nav_*}), so settings such as the travel-bubble cap,
 * regional overrides, recheck interval and planning concurrency can be tuned from numbers. Counters and histograms
 * can be fed from any thread; NPC counts by state are published as a snapshot from the main thread
 * ({@link #publishStates}), because a scrape runs on the HTTP thread. Records nothing until {@link #setEnabled}.
 */
public final class NpcNavigationMetrics implements MetricsCollector {

    /** Things that happen to NPCs; the metric label is the lower-case name. */
    public enum Event {
        SPAWNED, REMOVED, ARRIVED,
        /**
         * A stuck spell started that the engine reported first: its stationary check, or a path search that found
         * nothing (implementation plan 4b.2 item 12). One per spell, not one per report.
         */
        STUCK_ENGINE,
        /** A stuck spell started that K&K's progress watchdog reported first (NAV-17). One per spell. */
        STUCK_NO_PROGRESS,
        /** A stuck spell ended freed: the sum of the {@code FREED_*} events (kept for dashboards). */
        RECOVERED,
        /** A stuck spell ended freed by walking back along the NPC's own last footsteps. */
        FREED_BACK_OFF,
        /** A stuck spell ended freed by a step away from the blocking face. */
        FREED_SIDE_STEP,
        /** A stuck spell ended freed by one local leg to a point ahead on the NPC's own floor. */
        FREED_LOCAL_LEG,
        /** A stuck spell ended freed by planning the route again. */
        FREED_REPLAN,
        /** A stuck spell ended without the NPC getting free (its order failed, ended or was replaced). */
        NOT_FREED,
        /** The engine's path search for a recovery step found no path: that step failed (not a new spell). */
        RECOVERY_NO_PATH,
        /** A local leg was found by K&K's own search on the walk grid and walked as a waypoint list (link M6). */
        LOCAL_LEG_KNK,
        /** A local leg was walked with the engine's own path search (key off, or K&K's search found nothing). */
        LOCAL_LEG_ENGINE,
        /** A local leg found no path at all: K&K's search found nothing and the engine's search ended without one. */
        LOCAL_LEG_NONE,
        /** A navigating NPC's feet dropped 3 or more blocks between two looks while it was off the ground. */
        FELL,
        /** Recovery steps used up (DESIGN §6): the route is planned again. */
        RETRIES_EXHAUSTED,
        /** An NPC entered a region the current rules deny it (must stay 0). */
        TRESPASS,
        /** The periodic recheck found a denied region or a closed gate ahead: the route was planned again. */
        REPLAN_RECHECK,
        /** A gate or availability event made an NPC plan again: its route crosses what changed, or it waited on it. */
        REPLAN_EVENT,
        /** No allowed route: the NPC waits (NAV-03). */
        NO_ROUTE_WAIT,
        /** Chunk unloaded / loaded again (NAV-11). */
        PAUSED, RESUMED,
        /** A stretch of the route was handed to the mover (NAV-14). */
        STRETCH,
        /** The way ahead reaches a chunk that is not loaded: the NPC waits at the edge (NAV-12). */
        UNLOADED_WAIT,
        /** The engine added a block rule of its own to a local search; it was removed (must stay 0). */
        EXAMINER_LEAK,
        /** An order ended as failed (no road connects, retries and replans used up). */
        FAILED,
        /** One NPC's way ahead and position were checked against the current rules (NAV-02). */
        RULE_CHECK,
        /** A blocked NPC set out on the partial route to its blocking element (NAV-04). */
        BLOCKED_WALK,
        /** An NPC waiting at a blocking element was woken by an event instead of its safety timer. */
        WOKEN,
        /** A group folded into a column where its path is too narrow for 2 abreast (NAV-05, NAV-22). */
        GROUP_FOLD,
        /** A folded group took up 2 abreast or more again. */
        GROUP_REFORM,
        /** A group stepped down to a narrower width that is still 2 abreast or more (NAV-22). */
        GROUP_NARROW,
        /** A group of 2 abreast or more took up a wider width again (NAV-22). */
        GROUP_WIDEN,
        /** A follower finished its trip to its slot at the goal after the leader had arrived (NAV-23). */
        GROUP_FINISH_TRIP,
        /** A follower that fell off its lane or got stuck planned its own leg back to the group's path. */
        GROUP_REJOIN,
        /** A group's leader waited for followers that had fallen too far behind. */
        GROUP_REGROUP,
        /** A follower's own legs back failed repeatedly: the group goes on without it, it keeps trying slowly. */
        GROUP_LOST
    }

    /**
     * Why a group gave up a width (implementation plan 4c.3 decision 2.8, link M1: counted and logged only, no
     * behaviour depends on it), judged at the first path point where the width it gave up does not fit; the metric
     * label is the lower-case name.
     */
    public enum FoldReason {
        /** The path climbs or drops a block or more there: L4's rule makes a flight of stairs a column. */
        STAIR,
        /** No floor (or an unloaded chunk) where a lane would stand, or on the way out to it: the walk is too narrow. */
        NARROW,
        /** A gate door on the leader's routes is there (its box, with the followers' door margin). */
        DOOR,
        /** A lane point has floor but other WorldGuard regions than the path's own point (a structure, a district edge). */
        RULES,
        /** A lane point has floor but not {@code LANE_CLEARANCE} to its side: a jutting wall piece breaks the body's room. */
        EDGE;

        /** The lower-case name used in the log, the stats and the metric label. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The stats line of the local legs (link M6): how many K&K's own search found ({@code k&k}), how many the engine's
     * search walked ({@code engine}), how many found no path either way ({@code none}).
     */
    public static String localLegsLine(long knk, long engine, long none) {
        return String.format(Locale.ROOT, "local legs: k&k %d, engine %d, none %d", knk, engine, none);
    }

    /** Main-thread time per tick: 10 µs .. 50 ms. */
    static final double[] TICK_BOUNDS = {0.00001, 0.000025, 0.00005, 0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005,
        0.01, 0.025, 0.05};

    private final Map<Event, LongAdder> events = new EnumMap<>(Event.class);
    /** Per width change ({@link Event#GROUP_FOLD}, {@link Event#GROUP_NARROW}) the count per reason. */
    private final Map<Event, Map<FoldReason, LongAdder>> foldReasons = new EnumMap<>(Event.class);
    private final LabeledHistogram planSeconds = new LabeledHistogram("knk_npc_nav_plan_seconds",
        "Time to plan NPC routes, by kind (road: the road graph; local: a capture and local search).", "kind");
    private final LabeledHistogram queueWaitSeconds = new LabeledHistogram("knk_npc_nav_queue_wait_seconds",
        "Time an NPC waited for a planning slot.");
    private final LabeledHistogram mainThreadSeconds = new LabeledHistogram("knk_npc_nav_main_thread_seconds",
        "Main-thread time spent on NPC navigation per tick.", TICK_BOUNDS);
    private final SpellStats spells = new SpellStats();
    private volatile Map<String, Map<NpcNavState, Integer>> states = Map.of();
    private volatile int chunksHeld;
    private volatile boolean enabled;

    public NpcNavigationMetrics() {
        for (Event event : Event.values()) {
            events.put(event, new LongAdder());
        }
        for (Event change : WIDTH_CHANGES) {
            Map<FoldReason, LongAdder> byReason = new EnumMap<>(FoldReason.class);
            for (FoldReason reason : FoldReason.values()) {
                byReason.put(reason, new LongAdder());
            }
            foldReasons.put(change, byReason);
        }
        setEnabled(false);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        planSeconds.setEnabled(enabled);
        queueWaitSeconds.setEnabled(enabled);
        mainThreadSeconds.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void count(Event event) {
        count(event, 1);
    }

    public void count(Event event, long n) {
        if (enabled) {
            events.get(event).add(n);
        }
    }

    /** Running total (also while disabled: 0). */
    public long total(Event event) {
        return events.get(event).sum();
    }

    /** The width changes that carry a reason (decision 2.8): a fold and a narrowing. */
    public static final List<Event> WIDTH_CHANGES = List.of(Event.GROUP_FOLD, Event.GROUP_NARROW);

    /**
     * A group folded or narrowed for {@code reason} (decision 2.8): counts the reason under {@code change}
     * ({@link Event#GROUP_FOLD} or {@link Event#GROUP_NARROW}; the change itself is counted with {@link #count}).
     */
    public void countFoldReason(Event change, FoldReason reason) {
        Map<FoldReason, LongAdder> byReason = foldReasons.get(change);
        if (byReason == null) {
            throw new IllegalArgumentException("no reasons are counted for " + change);
        }
        if (enabled) {
            byReason.get(reason).increment();
        }
    }

    /** Running total of a width change's reason (also while disabled: 0). */
    public long foldReasonTotal(Event change, FoldReason reason) {
        Map<FoldReason, LongAdder> byReason = foldReasons.get(change);
        return byReason == null ? 0 : byReason.get(reason).sum();
    }

    /** The stuck spells' numbers for {@code /knk npc nav stats} (main thread). */
    public SpellStats spells() {
        return spells;
    }

    /**
     * A stuck spell started (implementation plan 4b.2 items 12 to 14): counts {@code STUCK_ENGINE} or
     * {@code STUCK_NO_PROGRESS} and one open spell. Main thread.
     */
    public void spellStarted(boolean engine, boolean nearWall) {
        count(engine ? Event.STUCK_ENGINE : Event.STUCK_NO_PROGRESS);
        if (enabled) {
            spells.opened(nearWall);
        }
    }

    /**
     * A stuck spell ended: {@code freedBy} is the {@code FREED_*} event of the step that worked, or
     * {@link Event#NOT_FREED}. Counts it (and {@code RECOVERED} for a freed one). Main thread.
     *
     * @param steps       recovery steps taken in the spell
     * @param stillTicks  ticks from the spell's start to its end
     */
    public void spellEnded(boolean nearWall, Event freedBy, int steps, long stillTicks, NavPoint at) {
        count(freedBy);
        if (freedBy != Event.NOT_FREED) {
            count(Event.RECOVERED);
        }
        if (enabled) {
            spells.ended(nearWall, freedBy, steps, stillTicks, at);
        }
    }

    /** {@code kind}: {@code road} or {@code local}. */
    public void observePlan(String kind, double seconds) {
        planSeconds.observe(seconds, kind);
    }

    public void observeQueueWait(double seconds) {
        queueWaitSeconds.observe(seconds);
    }

    public void observeMainThread(double seconds) {
        mainThreadSeconds.observe(seconds);
    }

    /** NPC counts by world and state; call from the main thread (e.g. once a second). The map is copied. */
    public void publishStates(Map<String, Map<NpcNavState, Integer>> byWorld) {
        Map<String, Map<NpcNavState, Integer>> copy = new java.util.HashMap<>();
        byWorld.forEach((world, counts) -> copy.put(world, Map.copyOf(counts)));
        this.states = Map.copyOf(copy);
    }

    /** Chunks held loaded for NPCs (the travel bubble, NAV-12). */
    public void publishChunksHeld(int chunks) {
        this.chunksHeld = chunks;
    }

    @Override
    public void collect(PrometheusWriter out) {
        if (!enabled) {
            return;
        }
        for (Map.Entry<String, Map<NpcNavState, Integer>> world : states.entrySet()) {
            for (NpcNavState state : NpcNavState.values()) {
                out.gauge("knk_npc_nav_npcs", "NPCs under K&K navigation, by world and state.",
                    world.getValue().getOrDefault(state, 0), "world", world.getKey(), "state",
                    state.name().toLowerCase(Locale.ROOT));
            }
        }
        out.gauge("knk_npc_nav_chunks_held", "Chunks held loaded for travelling NPCs (travel bubble).", chunksHeld);
        out.gauge("knk_npc_nav_stuck_spells_open", "Stuck spells under way (an NPC stuck and not freed yet).",
            spells.open());
        for (Map.Entry<Event, LongAdder> event : events.entrySet()) {
            out.counter("knk_npc_nav_events_total", "NPC navigation events by kind.", event.getValue().sum(),
                "event", event.getKey().name().toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<Event, Map<FoldReason, LongAdder>> change : foldReasons.entrySet()) {
            for (Map.Entry<FoldReason, LongAdder> reason : change.getValue().entrySet()) {
                out.counter("knk_npc_nav_group_width_reasons_total",
                    "Group folds and narrowings by the reason at the place the wider width did not fit.",
                    reason.getValue().sum(), "change", change.getKey() == Event.GROUP_FOLD ? "fold" : "narrow",
                    "reason", reason.getKey().label());
            }
        }
        planSeconds.collect(out);
        queueWaitSeconds.collect(out);
        mainThreadSeconds.collect(out);
    }

    /**
     * Stuck spells split by where they happened ("near a wall": a cell that stops a body touches the NPC's cell or
     * the next one in its walking direction, implementation plan 4b.2 item 13), how each ended, the steps they took
     * and the longest one. Main thread; {@link #reset} with the stats.
     */
    public static final class SpellStats {

        /** Outcomes in report order: back-off, side-step, local leg, replan, not freed. */
        public static final List<Event> OUTCOMES = List.of(Event.FREED_BACK_OFF, Event.FREED_SIDE_STEP,
            Event.FREED_LOCAL_LEG, Event.FREED_REPLAN, Event.NOT_FREED);

        private final long[] started = new long[2];
        private final long[][] ended = new long[2][OUTCOMES.size()];
        private long open;
        private long endedTotal;
        private long stepsSum;
        private int stepsMax;
        private long longestStillTicks;
        private NavPoint longestAt;

        void opened(boolean nearWall) {
            started[nearWall ? 1 : 0]++;
            open++;
        }

        void ended(boolean nearWall, Event outcome, int steps, long stillTicks, NavPoint at) {
            int i = OUTCOMES.indexOf(outcome);
            if (i >= 0) {
                ended[nearWall ? 1 : 0][i]++;
            }
            open = Math.max(0, open - 1);
            endedTotal++;
            stepsSum += steps;
            stepsMax = Math.max(stepsMax, steps);
            if (stillTicks > longestStillTicks) {
                longestStillTicks = stillTicks;
                longestAt = at;
            }
        }

        public void reset() {
            java.util.Arrays.fill(started, 0);
            for (long[] row : ended) {
                java.util.Arrays.fill(row, 0);
            }
            endedTotal = 0;
            stepsSum = 0;
            stepsMax = 0;
            longestStillTicks = 0;
            longestAt = null;
        }

        /** Spells started near a wall ({@code true}) or elsewhere since the last reset. */
        public long started(boolean nearWall) {
            return started[nearWall ? 1 : 0];
        }

        /** Spells near a wall or elsewhere that ended with {@code outcome}. */
        public long ended(boolean nearWall, Event outcome) {
            int i = OUTCOMES.indexOf(outcome);
            return i < 0 ? 0 : ended[nearWall ? 1 : 0][i];
        }

        /** Spells still open (not reset with the stats: they are under way). */
        public long open() {
            return open;
        }

        public double meanSteps() {
            return endedTotal == 0 ? 0 : (double) stepsSum / endedTotal;
        }

        public int maxSteps() {
            return stepsMax;
        }

        public long longestStillTicks() {
            return longestStillTicks;
        }

        /** Where the longest spell was, or null. */
        public NavPoint longestAt() {
            return longestAt;
        }

        /**
         * The stats line's stuck part (implementation plan 4b.2 item 14), without the falls and engine searches,
         * which come from the event counters.
         */
        public String describe(long engineNoPath, long falls) {
            return String.format(Locale.ROOT, "stuck near a wall: %d, freed by: %s | stuck elsewhere: %d (%s) | steps "
                    + "per stuck: mean %.1f, max %d | engine found no path %d | longest still %.1f s%s | falls %d",
                started(true), split(true), started(false), split(false), meanSteps(), maxSteps(), engineNoPath,
                longestStillTicks / 20.0, longestAt == null ? "" : String.format(Locale.ROOT, " at %d %d %d",
                    longestAt.blockX(), longestAt.blockY(), longestAt.blockZ()), falls);
        }

        private String split(boolean nearWall) {
            return String.format(Locale.ROOT, "back-off %d / side-step %d / local leg %d / replan %d, not freed %d",
                ended(nearWall, Event.FREED_BACK_OFF), ended(nearWall, Event.FREED_SIDE_STEP),
                ended(nearWall, Event.FREED_LOCAL_LEG), ended(nearWall, Event.FREED_REPLAN),
                ended(nearWall, Event.NOT_FREED));
        }
    }
}
