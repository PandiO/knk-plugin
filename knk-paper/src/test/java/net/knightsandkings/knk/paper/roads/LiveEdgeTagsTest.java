package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RoutingView;
import net.knightsandkings.knk.core.roads.route.TrailCentring;

/** Live edge tags (KNG-27 live test 2026-10-08, A7/A8 → findings N3/N4) and the routing view (rev. 7 Part A). */
class LiveEdgeTagsTest {

    private static final String WORLD = "world";

    private final AtomicReference<RoadNetworkSnapshot> stored = new AtomicReference<>(network(List.of()));
    private final Set<String> newRegionCells = new HashSet<>();
    private volatile GateCells gates = GateCells.NONE;
    private final List<String> changed = new ArrayList<>();
    private final List<Set<String>> warmed = new ArrayList<>();
    private final LiveEdgeTags tags = new LiveEdgeTags(w -> stored.get(), probe(), () -> 5, Runnable::run, Runnable::run,
        changed::add, warmed::add, () -> 0L);

    /** WorldGuard as the tests set it up: town_1 everywhere, newRegionCells at x 20-24; the gate cells of {@link #gates}. */
    private LiveEdgeTags.Probe probe() {
        return new LiveEdgeTags.Probe() {
            @Override
            public Set<String> regionsAt(String world, int x, int feetY, int z) {
                Set<String> out = new HashSet<>();
                out.add("town_1");
                if (x >= 20 && x <= 24 && feetY == 65) {
                    out.addAll(newRegionCells);
                }
                return out;
            }

            @Override
            public GateCells gates(String world) {
                return gates;
            }
        };
    }

    private static RoadNetworkSnapshot network(List<Integer> storedDoors) {
        RoadEdge road = new RoadEdge(7, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {40, 64, 0}), 40, 3,
            OptionalInt.empty(), OptionalInt.empty(), 1.0, Set.of(), storedDoors, List.of(), List.of("town_1"),
            RoadEdgeSource.RECORDED, false);
        return RoadNetworkSnapshot.builder(WORLD)
            .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addNode(new RoadNode(2, 40, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addEdge(road).build();
    }

    /** The pieces the routing view cut a stored edge into, From → To. */
    private static List<RoadEdge> pieces(RoadNetworkSnapshot view, int storedEdgeId) {
        return view.edges().stream().filter(e -> view.storedEdgeId(e.id()) == storedEdgeId)
            .sorted(Comparator.comparingDouble(e -> view.piece(e.id()).map(p -> p.fromAlong()).orElse(0.0))).toList();
    }

    private void runPass() {
        tags.refresh(WORLD);
        for (int i = 0; i < 100; i++) {
            tags.tick();
        }
    }

    @Test
    void aRegionMadeAfterTheBuildAndAnUntaggedGateReachTheRouter() {
        newRegionCells.add("domain_16");
        gates = (x, y, z) -> x == 30 && z == 0 && y == 65 ? OptionalInt.of(13) : OptionalInt.empty();

        assertSame(stored.get(), tags.snapshot(WORLD), "the stored tags until the first pass is done");
        runPass();

        // the routing view: cut at the new region's border (x 20-24, widened a sample each side) and at the door (x 30)
        RoadNetworkSnapshot view = tags.snapshot(WORLD);
        assertTrue(view.edge(7).isEmpty(), "the stored edge is replaced by its pieces");
        List<RoadEdge> pieces = pieces(view, 7);
        assertEquals(5, pieces.size());
        pieces.forEach(p -> assertTrue(p.id() >= RoutingView.FIRST_SYNTHETIC_ID));
        assertEquals(List.of(List.of("town_1"), List.of("town_1", "domain_16"), List.of("town_1"), List.of("town_1"),
            List.of("town_1")), pieces.stream().map(RoadEdge::regionIds).toList());
        assertEquals(List.of(List.of(), List.of(), List.of(), List.of(13), List.of()),
            pieces.stream().map(RoadEdge::gateDoorIds).toList());
        assertEquals(new RoadNetworkSnapshot.EdgePiece(7, 18, 26), view.piece(pieces.get(1).id()).orElseThrow());
        assertEquals(new RoadNetworkSnapshot.EdgePiece(7, 28.5, 30.5), view.piece(pieces.get(3).id()).orElseThrow());
        assertEquals(List.of(WORLD), changed, "navigation re-checks its routes once");
        assertEquals(List.of(Set.of("domain_16")), warmed, "the domain cache learns the new region");
        assertTrue(tags.describe().contains("+1 region, +1 gate door), 1 cut into 5 pieces"), tags.describe());
        assertEquals(List.of("town_1"), stored.get().requireEdge(7).regionIds(), "the stored network is untouched");
    }

    @Test
    void aRegionWhoseRuleIsIgnoredForRoadsCutsNothing() {
        // rev. 7 Part C + Part A: a house or shop along a public street; only the gate cuts the road here
        newRegionCells.add("domain_16");
        gates = (x, y, z) -> x == 30 && z == 0 && y == 65 ? OptionalInt.of(13) : OptionalInt.empty();
        LiveEdgeTags ignoring = new LiveEdgeTags(w -> stored.get(), probe(), () -> 5, Runnable::run, Runnable::run,
            changed::add, warmed::add, () -> 0L, regionId -> !regionId.equals("domain_16"));

        ignoring.refresh(WORLD);
        for (int i = 0; i < 100; i++) {
            ignoring.tick();
        }

        List<RoadEdge> pieces = pieces(ignoring.snapshot(WORLD), 7);
        assertEquals(3, pieces.size(), "before the door, the door, after it");
        pieces.forEach(p -> assertEquals(List.of("town_1"), p.regionIds()));
        assertEquals(List.of(List.of(), List.of(13), List.of()), pieces.stream().map(RoadEdge::gateDoorIds).toList());
        assertTrue(warmed.isEmpty(), "no new region to warm");
    }

    @Test
    void anUnchangedWorldDoesNotReRouteAgainButARemovedRegionDoes() {
        newRegionCells.add("domain_16");
        runPass();
        runPass();
        assertEquals(List.of(WORLD), changed, "a second pass with the same tags changes nothing");

        newRegionCells.clear();
        runPass();
        assertEquals(List.of(WORLD, WORLD), changed);
        assertEquals(List.of("town_1"), tags.snapshot(WORLD).requireEdge(7).regionIds());
    }

    @Test
    void aNewStoredNetworkIsServedAsStoredUntilItsPassIsDone() {
        newRegionCells.add("domain_16");
        runPass();
        RoadNetworkSnapshot rebuilt = network(List.of(13));
        stored.set(rebuilt);

        assertSame(rebuilt, tags.snapshot(WORLD));
        runPass();
        List<RoadEdge> pieces = pieces(tags.snapshot(WORLD), 7);
        assertEquals(3, pieces.size(), "cut at the region only: the stored door 13 is not in the world");
        assertEquals(List.of("town_1", "domain_16"), pieces.get(1).regionIds());
        pieces.forEach(p -> assertEquals(List.of(13), p.gateDoorIds(), "a stored door found nowhere stays on every piece"));
    }

    // ---- KNG-110 (P4): a region over part of the road's width ----------------------------------------

    /** The rows (z) a district covers at x 20-24; the road is three rows wide, z -1..1, its centre line on z = 0. */
    private final Set<Integer> districtRows = new HashSet<>();
    private boolean chunksLoaded = true;
    private final List<Runnable> chunkLoads = new ArrayList<>();
    private int regionLookups;

    private LiveEdgeTags.Probe wideProbe() {
        return new LiveEdgeTags.Probe() {
            @Override
            public Set<String> regionsAt(String world, int x, int feetY, int z) {
                regionLookups++;
                Set<String> out = new HashSet<>();
                out.add("town_1");
                if (x >= 20 && x <= 24 && feetY == 65 && districtRows.contains(z)) {
                    out.add("district_9");
                }
                return out;
            }

            @Override
            public GateCells gates(String world) {
                return GateCells.NONE;
            }

            @Override
            public TrailCentring.Ground ground(String world) {
                return new TrailCentring.Ground() {
                    @Override
                    public OptionalInt roadFloor(int x, int z, int nearY) {
                        return chunksLoaded && Math.abs(z) <= 1 && Math.abs(nearY - 64) <= 1 ? OptionalInt.of(64)
                            : OptionalInt.empty();
                    }

                    @Override
                    public boolean stairOrSlab(int x, int y, int z) {
                        return false;
                    }
                };
            }

            @Override
            public boolean loaded(String world, int x, int z) {
                return chunksLoaded;
            }

            @Override
            public void load(String world, int x, int z, Runnable then) {
                chunkLoads.add(then);
            }
        };
    }

    /** Live tags whose district keeps players off the road (when {@code restricts}) and a road surface. */
    private LiveEdgeTags wide(boolean restricts) {
        return new LiveEdgeTags(w -> stored.get(), wideProbe(), () -> 5, Runnable::run, Runnable::run, changed::add,
            warmed::add, () -> 0L, regionId -> true, regionId -> restricts && regionId.equals("district_9"));
    }

    private static void run(LiveEdgeTags tags) {
        tags.refresh(WORLD);
        for (int i = 0; i < 100; i++) {
            tags.tick();
        }
    }

    @Test
    void aDistrictOverTwoOfThreeRowsKeepsItsTagAndTheFreeRowsLane() {
        districtRows.addAll(Set.of(0, 1));
        LiveEdgeTags tags = wide(true);

        run(tags);

        List<RoadEdge> pieces = pieces(tags.snapshot(WORLD), 7);
        assertEquals(3, pieces.size());
        assertEquals(List.of("town_1", "district_9"), pieces.get(1).regionIds(), "the centre line is in the district");
        assertEquals(List.of(List.of("town_1")), pieces.get(1).lanes(), "row z = -1 is free");
        assertTrue(pieces.get(0).lanes().isEmpty() && pieces.get(2).lanes().isEmpty());
        assertTrue(tags.describe().contains("1 with lanes"), tags.describe());
    }

    @Test
    void aDistrictOverTheWholeWidthBlocksAsBefore() {
        districtRows.addAll(Set.of(-1, 0, 1));
        LiveEdgeTags tags = wide(true);

        run(tags);

        List<RoadEdge> pieces = pieces(tags.snapshot(WORLD), 7);
        assertEquals(3, pieces.size());
        assertEquals(List.of("town_1", "district_9"), pieces.get(1).regionIds());
        pieces.forEach(p -> assertTrue(p.lanes().isEmpty(), "no free row: the centre line decides"));
    }

    @Test
    void aDistrictOverOneOuterRowDoesNotTouchTheRoad() {
        districtRows.add(1);
        LiveEdgeTags tags = wide(true);

        run(tags);

        assertSame(stored.get(), tags.snapshot(WORLD), "nothing new: the stored edge as it is");
        assertEquals(List.of(), changed);
    }

    @Test
    void onlyARestrictingRegionMakesThePassLookAcross() {
        districtRows.addAll(Set.of(0, 1));
        LiveEdgeTags open = wide(false);

        run(open);

        assertEquals(21, regionLookups, "one lookup per sample, none across the road");
        pieces(open.snapshot(WORLD), 7).forEach(p -> assertTrue(p.lanes().isEmpty()));

        regionLookups = 0;
        run(wide(true));
        assertEquals(21 + 3 * 2, regionLookups, "two cells across at each of the three samples in the district");
    }

    @Test
    void aRegionTheDomainCacheDoesNotKnowIsLookedAcrossToo() {
        // live test 2026-10-10 (G2): after /knk cache refresh the cache did not know domain_17, and the road stayed shut
        java.util.Map<String, net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot> cache =
            new java.util.HashMap<>();
        cache.put("town_1", domain("town_1", true, true));
        cache.put("jail_2", domain("jail_2", true, false));
        cache.put("keep_3", domain("keep_3", false, true));
        java.util.function.Predicate<String> restricts = LiveEdgeTags.restrictsByDomain(
            regionId -> java.util.Optional.ofNullable(cache.get(regionId)),
            new net.knightsandkings.knk.core.regions.DomainAccessEvaluator());

        assertTrue(!restricts.test("town_1"), "open both ways: the centre line is enough");
        assertTrue(restricts.test("jail_2") && restricts.test("keep_3"));
        assertTrue(restricts.test("district_9"), "not known: look across");

        districtRows.addAll(Set.of(0, 1));
        LiveEdgeTags tags = new LiveEdgeTags(w -> stored.get(), wideProbe(), () -> 5, Runnable::run, Runnable::run,
            changed::add, warmed::add, () -> 0L, regionId -> true, restricts);
        run(tags);
        assertEquals(List.of(List.of("town_1")), pieces(tags.snapshot(WORLD), 7).get(1).lanes());
    }

    private static net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot domain(String region,
                                                                                                 boolean entry, boolean exit) {
        return new net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot(1, region, null, region,
            entry, exit, "District", Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void aCrossSectionInUnloadedChunksLoadsThemAndIsRememberedForTheNextPass() {
        districtRows.addAll(Set.of(0, 1));
        chunksLoaded = false;
        LiveEdgeTags tags = wide(true);

        run(tags);
        pieces(tags.snapshot(WORLD), 7).forEach(p -> assertTrue(p.lanes().isEmpty(), "not known yet: the centre line"));
        assertEquals(6, chunkLoads.size(), "three samples, two chunks each (z -3..3 spans chunks -1 and 0)");

        chunksLoaded = true; // the chunks arrive ...
        chunkLoads.forEach(Runnable::run);
        chunksLoaded = false; // ... and unload again before the next pass
        run(tags);

        assertEquals(List.of(List.of("town_1")), pieces(tags.snapshot(WORLD), 7).get(1).lanes(), "remembered");
        assertEquals(6, chunkLoads.size(), "nothing loaded twice");
    }

    @Test
    void thePassSpendsTheLookupBudgetPerTick() {
        tags.refresh(WORLD);
        tags.tick();
        assertTrue(tags.describe().contains("tagging 0/1"), tags.describe());
        for (int i = 0; i < 10; i++) {
            tags.tick();
        }
        assertTrue(tags.describe().contains("0 edge(s) with extra tags"), tags.describe());
        assertEquals(List.of(), changed, "nothing new: no re-route");
    }
}
