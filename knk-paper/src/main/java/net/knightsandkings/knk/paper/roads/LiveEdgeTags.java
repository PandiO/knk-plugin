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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.build.EdgeTagging;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RoutingView;
import net.knightsandkings.knk.core.roads.route.RoutingView.Hit;
import net.knightsandkings.knk.core.roads.route.RoutingView.Span;
import net.knightsandkings.knk.core.roads.route.TrailCentring;

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
 *
 * <p>Rev. 7 Part A (REV7_PROPOSAL §2): the pass records <em>where</em> along each edge a region or a gate
 * door is met, and the snapshot it builds is the {@link RoutingView} - every edge cut where its tags change,
 * so a gate door is its own short piece and a region border cuts the road. Navigation routes on that view;
 * the admin side keeps the stored snapshot. A region whose domain's entry rule does not apply to roads (rev. 7
 * Part C, "Ignored": a house or shop along a public street) cuts nothing: {@code cutsRoads} leaves it out of the
 * samples, and the router ignores its rule anyway.
 *
 * <p>KNG-110 (P4): where a sample's centre is in a region whose domain keeps someone off the road
 * ({@code restricts}), the pass also looks across the road - the trail's road cells ({@link TrailCentring#across},
 * up to {@link TrailCentring#MAX_HALF_WIDTH} each side) and the regions at each - and records the road's
 * {@linkplain net.knightsandkings.knk.core.domain.roads.RoadEdge#lanes lanes} when they differ from the centre's, so
 * a region over part of the width blocks the road only where it leaves no free gap. A cross-section is remembered
 * once its chunks were loaded ({@link CrossSections}). Each region lookup across counts against the budget.
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

        /**
         * KNG-110: the road surface of a world (the trail's road-cell rule, {@code RoadSurfaceGround}); null judges
         * regions on the centre line only. It may answer only in loaded chunks.
         */
        default TrailCentring.Ground ground(String world) {
            return null;
        }

        /** KNG-110: whether the chunk holding block {@code (x, z)} is loaded. */
        default boolean loaded(String world, int x, int z) {
            return true;
        }

        /**
         * KNG-110: loads the chunk holding block {@code (x, z)} in the background, without generating it; {@code then}
         * runs on the main thread when that is done (or failed).
         */
        default void load(String world, int x, int z, Runnable then) {
            then.run();
        }
    }

    private record State(RoadNetworkSnapshot base, RoadNetworkSnapshot tagged, Map<Integer, String> signature,
                         int regionsAdded, int doorsAdded, int edgesCut, int pieces, int laned, long finishedAt) {
    }

    private final Function<String, RoadNetworkSnapshot> stored;
    private final Probe probe;
    private final IntSupplier budget;
    private final Executor builder;
    private final Executor mainThread;
    private final Consumer<String> onChanged;
    private final Consumer<Set<String>> onRegions;
    private final LongSupplier clock;
    private final Predicate<String> cutsRoads;
    private final Predicate<String> restricts;
    private final CrossSections crossSections;

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
        this(stored, probe, budget, builder, mainThread, onChanged, onRegions, clock, regionId -> true);
    }

    /**
     * @param cutsRoads whether a region may cut a road (main thread, per sampled region): false for a region whose
     *                  domain's entry rule is "Ignored" for roads (rev. 7 Part C); a region of unknown domain cuts
     */
    public LiveEdgeTags(Function<String, RoadNetworkSnapshot> stored, Probe probe, IntSupplier budget, Executor builder,
                        Executor mainThread, Consumer<String> onChanged, Consumer<Set<String>> onRegions, LongSupplier clock,
                        Predicate<String> cutsRoads) {
        this(stored, probe, budget, builder, mainThread, onChanged, onRegions, clock, cutsRoads, regionId -> true);
    }

    /**
     * @param restricts whether a region's domain keeps anyone off the road (main thread, per sampled region; KNG-110):
     *                  only where the centre line meets such a region is the road's width looked at, with the
     *                  {@linkplain Probe#ground road surface}
     */
    public LiveEdgeTags(Function<String, RoadNetworkSnapshot> stored, Probe probe, IntSupplier budget, Executor builder,
                        Executor mainThread, Consumer<String> onChanged, Consumer<Set<String>> onRegions, LongSupplier clock,
                        Predicate<String> cutsRoads, Predicate<String> restricts) {
        this.cutsRoads = Objects.requireNonNull(cutsRoads, "cutsRoads");
        this.restricts = Objects.requireNonNull(restricts, "restricts");
        this.crossSections = new CrossSections(Objects.requireNonNull(probe, "probe"));
        this.stored = Objects.requireNonNull(stored, "stored");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
        this.onRegions = Objects.requireNonNull(onRegions, "onRegions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * KNG-110: the {@code restricts} rule from the domain cache - a region whose domain denies entry or exit, and a
     * region the cache does not know. Live test 2026-10-10 (G2): after {@code /knk cache refresh} and a reload, the
     * cache did not know {@code domain_17} (a batch warm-up answers one district only), so the pass judged it on the
     * centre line and the road stayed blocked until a route had looked the region up. Looking across at an unknown
     * region costs a few lookups; the lanes it finds only open roads the region's rule would close.
     *
     * @param domains the cached domain of a region (main thread, no API call)
     */
    public static Predicate<String> restrictsByDomain(Function<String, Optional<DomainSnapshot>> domains,
                                                     DomainAccessEvaluator evaluator) {
        return regionId -> domains.apply(regionId)
            .map(domain -> evaluator.entry(domain).isPresent() || evaluator.exit(domain).isPresent())
            .orElse(true);
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
                previous.doorsAdded, previous.edgesCut, previous.pieces, previous.laned, clock.getAsLong()));
            return; // nothing changed since the last pass: no rebuild, no re-route
        }
        if (!pass.newRegions.isEmpty()) {
            onRegions.accept(Set.copyOf(pass.newRegions));
        }
        Map<Integer, List<Span>> changed = pass.changed;
        builder.execute(() -> {
            RoadNetworkSnapshot tagged;
            try {
                tagged = changed.isEmpty() ? pass.base : RoutingView.build(pass.base, changed);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Roads] Could not build the live-tagged network of " + pass.world, e);
                return;
            }
            mainThread.execute(() -> {
                if (stored.apply(pass.world) != pass.base) {
                    return;
                }
                states.put(pass.world, new State(pass.base, tagged, pass.signature, pass.regionsAdded, pass.doorsAdded,
                    pass.edgesCut, pass.pieces, pass.laned, clock.getAsLong()));
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
            parts.add(String.format(Locale.ROOT,
                "%s: %d edge(s) with extra tags (+%d region, +%d gate door), %d cut into %d pieces, %d with lanes (%d cross-sections known), %d s ago",
                e.getKey(), s.signature.size(), s.regionsAdded, s.doorsAdded, s.edgesCut, s.pieces, s.laned, crossSections.size(),
                Math.max(0, (now - s.finishedAt) / 1000)));
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
        final Map<Integer, List<Span>> changed = new HashMap<>();
        final Map<Integer, String> signature = new HashMap<>();
        final Set<String> newRegions = new LinkedHashSet<>();
        int regionsAdded;
        int doorsAdded;
        int edgesCut;
        int pieces;
        int laned;
        int edgeIndex;
        final TrailCentring.Ground ground;
        List<EdgeTagging.Sample> samples;
        int sampleIndex;
        List<Hit<String>> regions;
        List<Hit<List<String>>> lanes;

        Pass(String world, RoadNetworkSnapshot base, GateCells gates) {
            this.world = world;
            this.base = base;
            this.gates = gates == null ? GateCells.NONE : gates;
            this.edges = base.edges();
            this.ground = probe.ground(world);
        }

        boolean done() {
            return edgeIndex >= edges.size();
        }

        /** Spends up to {@code left} region lookups; returns what is left. */
        int advance(int left) {
            while (left > 0 && !done()) {
                RoadEdge edge = edges.get(edgeIndex);
                if (samples == null) {
                    samples = EdgeTagging.alongSamples(edge.geometry(), EdgeTagging.REGION_STEP);
                    sampleIndex = 0;
                    regions = new ArrayList<>(samples.size());
                    lanes = new ArrayList<>(samples.size());
                }
                while (left > 0 && sampleIndex < samples.size()) {
                    EdgeTagging.Sample s = samples.get(sampleIndex);
                    Set<String> at = regionsAt(s.x(), s.y(), s.z());
                    regions.add(new Hit<>(s.along(), at));
                    left--;
                    Set<List<String>> across = Set.of();
                    if (ground != null && at.stream().anyMatch(restricts)) {
                        List<TrailCentring.Cell> cells = crossSections.at(world, ground, samples, sampleIndex);
                        left -= cells.size();
                        across = lanesAcross(at, cells);
                    }
                    lanes.add(new Hit<>(s.along(), across));
                    sampleIndex++;
                }
                if (sampleIndex >= samples.size()) {
                    finishEdge(edge);
                    samples = null;
                    edgeIndex++;
                }
            }
            return left;
        }

        /** The regions that may cut a road at a floor block (feet level). */
        private Set<String> regionsAt(int x, int floorY, int z) {
            return probe.regionsAt(world, x, floorY + 1, z).stream().filter(cutsRoads).collect(Collectors.toSet());
        }

        /**
         * KNG-110: the lanes of a sample whose centre has {@code centre}, from the road cells across it; none when the
         * centre line decides anyway (every cell has the centre's regions, or the width is not known).
         */
        private Set<List<String>> lanesAcross(Set<String> centre, List<TrailCentring.Cell> cells) {
            if (cells.size() < 2) {
                return Set.of();
            }
            List<Set<String>> sets = new ArrayList<>(cells.size());
            for (TrailCentring.Cell cell : cells) {
                sets.add(cell.k() == 0 ? centre : regionsAt(cell.x(), cell.y(), cell.z()));
            }
            List<List<String>> minimal = RoutingView.minimalLanes(sets);
            return minimal.equals(List.of(centre.stream().sorted().toList())) ? Set.of() : Set.copyOf(minimal);
        }

        private void finishEdge(RoadEdge edge) {
            List<EdgeTagging.Sample> doorSamples = EdgeTagging.alongSamples(edge.geometry(), EdgeTagging.DOOR_STEP);
            List<Set<Integer>> doorsAt = EdgeTagging.doorsAt(doorSamples, gates);
            List<Hit<Integer>> doors = new ArrayList<>(doorSamples.size());
            for (int i = 0; i < doorSamples.size(); i++) {
                doors.add(new Hit<>(doorSamples.get(i).along(), doorsAt.get(i)));
            }
            List<Span> spans = RoutingView.spans(edge, base.polyline(edge).length(), regions, lanes, doors);
            if (spans.size() == 1 && spans.get(0).regionIds().equals(edge.regionIds())
                && spans.get(0).gateDoorIds().equals(edge.gateDoorIds()) && spans.get(0).lanes().isEmpty()) {
                return; // the stored tags, the whole edge long
            }
            Set<String> allRegions = new LinkedHashSet<>();
            Set<Integer> allDoors = new LinkedHashSet<>();
            StringBuilder sig = new StringBuilder();
            for (Span span : spans) {
                allRegions.addAll(span.regionIds());
                span.lanes().forEach(allRegions::addAll);
                allDoors.addAll(span.gateDoorIds());
                sig.append(String.format(Locale.ROOT, "%.1f-%.1f:%s|%s|%s;", span.from(), span.to(), span.regionIds(),
                    span.gateDoorIds(), span.lanes()));
            }
            allRegions.removeAll(edge.regionIds());
            allDoors.removeAll(edge.gateDoorIds());
            regionsAdded += allRegions.size();
            doorsAdded += allDoors.size();
            newRegions.addAll(allRegions);
            if (spans.size() > 1) {
                edgesCut++;
                pieces += spans.size();
            }
            laned += (int) spans.stream().filter(span -> !span.lanes().isEmpty()).count();
            changed.put(edge.id(), spans);
            signature.put(edge.id(), sig.toString());
        }
    }
}
