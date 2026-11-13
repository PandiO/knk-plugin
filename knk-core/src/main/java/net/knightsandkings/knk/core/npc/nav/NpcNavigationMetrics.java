package net.knightsandkings.knk.core.npc.nav;

import java.util.EnumMap;
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
        /** The engine's own stuck detection fired. */
        STUCK_ENGINE,
        /** K&K's progress watchdog fired (NAV-17). */
        STUCK_NO_PROGRESS,
        /** A recovery leg brought the NPC back on its route. */
        RECOVERED,
        /** Bounded retries used up (DESIGN §6). */
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
        /** A group folded into a column where its path is too narrow for its formation (NAV-05). */
        GROUP_FOLD,
        /** A folded group took up its formation again. */
        GROUP_REFORM,
        /** A follower that fell off its lane or got stuck planned its own leg back to the group's path. */
        GROUP_REJOIN,
        /** A group's leader waited for followers that had fallen too far behind. */
        GROUP_REGROUP,
        /** A follower's own legs back failed repeatedly: the group goes on without it, it keeps trying slowly. */
        GROUP_LOST
    }

    /** Main-thread time per tick: 10 µs .. 50 ms. */
    static final double[] TICK_BOUNDS = {0.00001, 0.000025, 0.00005, 0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005,
        0.01, 0.025, 0.05};

    private final Map<Event, LongAdder> events = new EnumMap<>(Event.class);
    private final LabeledHistogram planSeconds = new LabeledHistogram("knk_npc_nav_plan_seconds",
        "Time to plan NPC routes, by kind (road: the road graph; local: a capture and local search).", "kind");
    private final LabeledHistogram queueWaitSeconds = new LabeledHistogram("knk_npc_nav_queue_wait_seconds",
        "Time an NPC waited for a planning slot.");
    private final LabeledHistogram mainThreadSeconds = new LabeledHistogram("knk_npc_nav_main_thread_seconds",
        "Main-thread time spent on NPC navigation per tick.", TICK_BOUNDS);
    private volatile Map<String, Map<NpcNavState, Integer>> states = Map.of();
    private volatile int chunksHeld;
    private volatile boolean enabled;

    public NpcNavigationMetrics() {
        for (Event event : Event.values()) {
            events.put(event, new LongAdder());
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
        for (Map.Entry<Event, LongAdder> event : events.entrySet()) {
            out.counter("knk_npc_nav_events_total", "NPC navigation events by kind.", event.getValue().sum(),
                "event", event.getKey().name().toLowerCase(Locale.ROOT));
        }
        planSeconds.collect(out);
        queueWaitSeconds.collect(out);
        mainThreadSeconds.collect(out);
    }
}
