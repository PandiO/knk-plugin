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

/** Live edge tags (KNG-27 live test 2026-10-08, A7/A8 → findings N3/N4) and the routing view (rev. 7 Part A). */
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
