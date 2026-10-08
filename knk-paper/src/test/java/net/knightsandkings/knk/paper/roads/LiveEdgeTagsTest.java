package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

/** Live edge tags (KNG-27 live test 2026-10-08, A7/A8 → findings N3/N4). */
class LiveEdgeTagsTest {

    private static final String WORLD = "world";

    private final AtomicReference<RoadNetworkSnapshot> stored = new AtomicReference<>(network(List.of()));
    private final Set<String> newRegionCells = new HashSet<>();
    private volatile GateCells gates = GateCells.NONE;
    private final List<String> changed = new ArrayList<>();
    private final List<Set<String>> warmed = new ArrayList<>();
    private final LiveEdgeTags tags = new LiveEdgeTags(w -> stored.get(), new LiveEdgeTags.Probe() {
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
    }, () -> 5, Runnable::run, Runnable::run, changed::add, warmed::add, () -> 0L);

    private static RoadNetworkSnapshot network(List<Integer> storedDoors) {
        RoadEdge road = new RoadEdge(7, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {40, 64, 0}), 40, 3,
            OptionalInt.empty(), OptionalInt.empty(), 1.0, Set.of(), storedDoors, List.of(), List.of("town_1"),
            RoadEdgeSource.RECORDED, false);
        return RoadNetworkSnapshot.builder(WORLD)
            .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addNode(new RoadNode(2, 40, 64, 0, RoadNodeKind.JUNCTION, null, 1, false))
            .addEdge(road).build();
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

        RoadEdge edge = tags.snapshot(WORLD).requireEdge(7);
        assertEquals(List.of("town_1", "domain_16"), edge.regionIds());
        assertEquals(List.of(13), edge.gateDoorIds());
        assertEquals(List.of(WORLD), changed, "navigation re-checks its routes once");
        assertEquals(List.of(Set.of("domain_16")), warmed, "the domain cache learns the new region");
        assertTrue(tags.describe().contains("+1 region, +1 gate door"), tags.describe());
        assertEquals(List.of("town_1"), stored.get().requireEdge(7).regionIds(), "the stored network is untouched");
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
        assertEquals(List.of("town_1", "domain_16"), tags.snapshot(WORLD).requireEdge(7).regionIds());
        assertEquals(List.of(13), tags.snapshot(WORLD).requireEdge(7).gateDoorIds(), "stored doors are kept");
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
