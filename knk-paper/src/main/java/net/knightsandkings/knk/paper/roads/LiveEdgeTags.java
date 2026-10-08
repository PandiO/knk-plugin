package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.build.EdgeTagging;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * Live world tags for the router (KNG-27 live test 2026-10-08, findings N3/N4): every edge's WorldGuard
 * regions and gate doors as the world has them <em>now</em>, added to the tags the build or the
 * recording stored. A domain region created after the build (district 16) or a gate the recording
 * never tagged (the South Gate road) is then seen by {@code DomainAvailability} and
 * {@code GateAvailability} without a rebuild or a database write.
 *
 * <p>A pass samples each edge ({@link EdgeTagging#REGION_STEP} for regions at feet level, every block
 * for gate doors) on the main thread within a lookup budget per tick, like the build's tagging. It runs
 * when a world's network snapshot changes and every {@link #INTERVAL_TICKS}; when it finds tags that
 * differ from the last pass, the tagged snapshot is built on {@code builder} and swapped in, and
 * {@code onChanged} lets navigation re-check its routes. Until the first pass of a snapshot finishes,
 * {@link #snapshot} answers the stored one.
 */
public final class LiveEdgeTags {

    private static final Logger LOGGER = Logger.getLogger(LiveEdgeTags.class.getName());

    /** How often every world is re-tagged (a region edited in WorldGuard is seen within this). */
    public static final long INTERVAL_TICKS = 60 * 20L;
    public static final int LOOKUPS_PER_TICK = 200;
    public static final int LOOKUPS_PER_TICK_LAGGING = 50;

    /** The world side, main thread: WorldGuard regions at a block (feet level), a world's gate cells. */
    public interface Probe {
        Set<String> regionsAt(String world, int x, int feetY, int z);

        GateCells gates(String world);
    }

    private record State(RoadNetworkSnapshot base, RoadNetworkSnapshot tagged, Map<Integer, String> signature,
                         int regionsAdded, int doorsAdded, long finishedAt) {
    }

    private final Function<String, RoadNetworkSnapshot> stored;
    private final Probe probe;
    private final IntSupplier budget;
    private final Executor builder;
    private final Executor mainThread;
    private final Consumer<String> onChanged;
    private final Consumer<Set<String>> onRegions;
    private final LongSupplier clock;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Pass> passes = new LinkedHashMap<>();

    /**
     * @param stored     the stored network per world ({@code RoadNetworkCache::snapshot})
     * @param probe      WorldGuard and the gate cache
     * @param budget     lookups per tick ({@link #LOOKUPS_PER_TICK}, fewer under lag)
     * @param builder    where a tagged snapshot is built (off the main thread on the server)
     * @param mainThread back to the main thread
     * @param onChanged  a world's tags changed: re-check the routes ({@code NavigationService::onNetworkChanged})
     * @param onRegions  regions the live tags added: warm the domain cache for them
     * @param clock      millis, for the status line
     */
    public LiveEdgeTags(Function<String, RoadNetworkSnapshot> stored, Probe probe, IntSupplier budget, Executor builder,
                        Executor mainThread, Consumer<String> onChanged, Consumer<Set<String>> onRegions, LongSupplier clock) {
        this.stored = Objects.requireNonNull(stored, "stored");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
        this.onRegions = Objects.requireNonNull(onRegions, "onRegions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The world's network with live tags (any thread); the stored one until its first pass is done. */
    public RoadNetworkSnapshot snapshot(String world) {
        RoadNetworkSnapshot base = stored.apply(world);
        State state = states.get(world);
        return state != null && state.base == base ? state.tagged : base;
    }

    /** Main thread: (re)start a pass over the world's stored network. */
    public void refresh(String world) {
        RoadNetworkSnapshot base = stored.apply(world);
        if (base == null || base.isEmpty()) {
            passes.remove(world);
            states.remove(world);
            return;
        }
        passes.put(world, new Pass(world, base, probe.gates(world)));
    }

    /** Main thread: a pass over every world with a network. */
    public void refreshAll(Collection<String> worlds) {
        worlds.forEach(this::refresh);
    }

    /** Main thread, every tick: advance the running passes within the budget. */
    public void tick() {
        if (passes.isEmpty()) {
            return;
        }
        int left = Math.max(1, budget.getAsInt());
        Iterator<Pass> it = passes.values().iterator();
        while (it.hasNext() && left > 0) {
            Pass pass = it.next();
            try {
                left = pass.advance(left);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Roads] Live tagging of " + pass.world + " failed", e);
                it.remove();
                continue;
            }
            if (pass.done()) {
                it.remove();
                finish(pass);
            }
        }
    }

    private void finish(Pass pass) {
        if (stored.apply(pass.world) != pass.base) {
            return; // the network changed meanwhile; its own pass follows
        }
        State previous = states.get(pass.world);
        if (previous != null && previous.base == pass.base && previous.signature.equals(pass.signature)) {
            states.put(pass.world, new State(previous.base, previous.tagged, previous.signature, previous.regionsAdded,
                previous.doorsAdded, clock.getAsLong()));
            return; // nothing changed since the last pass: no rebuild, no re-route
        }
        if (!pass.newRegions.isEmpty()) {
            onRegions.accept(Set.copyOf(pass.newRegions));
        }
        Map<Integer, RoadEdge> changed = pass.changed;
        builder.execute(() -> {
            RoadNetworkSnapshot tagged;
            try {
                tagged = changed.isEmpty() ? pass.base : pass.base.retag(e -> changed.getOrDefault(e.id(), e));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Roads] Could not build the live-tagged network of " + pass.world, e);
                return;
            }
            mainThread.execute(() -> {
                if (stored.apply(pass.world) != pass.base) {
                    return;
                }
                states.put(pass.world, new State(pass.base, tagged, pass.signature, pass.regionsAdded, pass.doorsAdded,
                    clock.getAsLong()));
                if (!changed.isEmpty() || previous != null && !previous.signature.isEmpty()) {
                    onChanged.accept(pass.world);
                }
            });
        });
    }

    /** One line for {@code /knk road status}. */
    public String describe() {
        if (states.isEmpty() && passes.isEmpty()) {
            return "no network tagged yet";
        }
        List<String> parts = new ArrayList<>();
        long now = clock.getAsLong();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            parts.add(String.format(Locale.ROOT, "%s: %d edge(s) with extra tags (+%d region, +%d gate door), %d s ago",
                e.getKey(), s.signature.size(), s.regionsAdded, s.doorsAdded, Math.max(0, (now - s.finishedAt) / 1000)));
        }
        for (Pass p : passes.values()) {
            parts.add(p.world + ": tagging " + p.edgeIndex + "/" + p.edges.size() + " edges");
        }
        return String.join("; ", parts);
    }

    /** One world's pass over its stored edges. */
    private final class Pass {
        final String world;
        final RoadNetworkSnapshot base;
        final GateCells gates;
        final List<RoadEdge> edges;
        final Map<Integer, RoadEdge> changed = new HashMap<>();
        final Map<Integer, String> signature = new HashMap<>();
        final Set<String> newRegions = new LinkedHashSet<>();
        int regionsAdded;
        int doorsAdded;
        int edgeIndex;
        List<int[]> samples;
        int sampleIndex;
        Set<String> regions;

        Pass(String world, RoadNetworkSnapshot base, GateCells gates) {
            this.world = world;
            this.base = base;
            this.gates = gates == null ? GateCells.NONE : gates;
            this.edges = base.edges();
        }

        boolean done() {
            return edgeIndex >= edges.size();
        }

        /** Spends up to {@code left} region lookups; returns what is left. */
        int advance(int left) {
            while (left > 0 && !done()) {
                RoadEdge edge = edges.get(edgeIndex);
                if (samples == null) {
                    samples = EdgeTagging.samples(edge.geometry(), EdgeTagging.REGION_STEP);
                    sampleIndex = 0;
                    regions = new LinkedHashSet<>();
                }
                while (left > 0 && sampleIndex < samples.size()) {
                    int[] s = samples.get(sampleIndex++);
                    regions.addAll(probe.regionsAt(world, s[0], s[1] + 1, s[2]));
                    left--;
                }
                if (sampleIndex >= samples.size()) {
                    finishEdge(edge);
                    samples = null;
                    edgeIndex++;
                }
            }
            return left;
        }

        private void finishEdge(RoadEdge edge) {
            List<Integer> doors = EdgeTagging.doorsAlong(edge.geometry(), gates);
            RoadEdge tagged = EdgeTagging.withExtraTags(edge, regions, doors);
            if (tagged == edge) {
                return;
            }
            int extraRegions = tagged.regionIds().size() - edge.regionIds().size();
            int extraDoors = tagged.gateDoorIds().size() - edge.gateDoorIds().size();
            regionsAdded += extraRegions;
            doorsAdded += extraDoors;
            newRegions.addAll(tagged.regionIds().subList(edge.regionIds().size(), tagged.regionIds().size()));
            changed.put(edge.id(), tagged);
            signature.put(edge.id(), tagged.regionIds() + "|" + tagged.gateDoorIds());
        }
    }
}
