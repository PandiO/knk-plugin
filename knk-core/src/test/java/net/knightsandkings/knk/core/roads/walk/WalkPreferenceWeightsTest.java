package net.knightsandkings.knk.core.roads.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.landmarks.LandmarkWeights;
import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * Off-road phase 5.2 (PandiO/knk-workspace#709, DESIGN § 5.4.3, § 5.4.4, A10 walk part): the walk search with landmark
 * preference weights. A hall 13 by 7; the straight way from (0, 1) to (12, 1) runs along its north wall.
 */
class WalkPreferenceWeightsTest {

    private static final String G = GridFixture.GRASS;

    private static WalkFixture hall() {
        return new WalkFixture().floor(0, 0, 12, 6, 64, G);
    }

    private static WalkRequest straightAcross(WalkFixture f) {
        return f.request(0, 64, 1, 12, 64, 1);
    }

    private static LandmarkWeights region(int x0, int z0, int x1, int z1, double preference) {
        return LandmarkWeights.of(List.of(new LandmarkWeights.Area(1,
            new LandmarkWeights.RegionArea(RegionShape.cuboid(x0, 60, z0, x1, 80, z1)), preference)), 4.0);
    }

    private static WalkRequest weighted(WalkRequest request, LandmarkWeights weights) {
        return request.withWeights(weights.within(request.reachBox()));
    }

    private static WalkPath found(WalkRequest request) {
        WalkResult result = new WalkSearch().find(request);
        assertEquals(WalkResult.Status.FOUND, result.status(), result.toString());
        return result.path().orElseThrow();
    }

    private static boolean anyCell(WalkPath path, java.util.function.IntBinaryOperator test) {
        for (int i = 0; i < path.size(); i++) {
            if (test.applyAsInt(BlockKey.x(path.cell(i)), BlockKey.z(path.cell(i))) != 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anAreaWithAPreferenceOfTwoIsWalkedAround() {
        WalkFixture f = hall();
        WalkPath plain = found(straightAcross(f));
        assertTrue(anyCell(plain, (x, z) -> x >= 3 && x <= 9 && z <= 3 ? 1 : 0), "without weights: straight across");
        assertEquals(12.0, plain.length(), 1e-9);

        WalkPath avoided = found(weighted(straightAcross(f), region(3, 0, 9, 3, 2.0)));

        assertFalse(anyCell(avoided, (x, z) -> x >= 3 && x <= 9 && z <= 3 ? 1 : 0), "A10: the weighted area is avoided");
        assertTrue(avoided.length() > plain.length(), "a longer way round");
    }

    @Test
    void anAreaWithAPreferenceBelowOneDrawsThePathIn() {
        WalkFixture f = hall();
        WalkPath drawn = found(weighted(straightAcross(f), region(2, 4, 10, 6, 0.25)));

        assertTrue(anyCell(drawn, (x, z) -> x >= 2 && x <= 10 && z >= 4 ? 1 : 0), "the cheap lane is taken");
    }

    @Test
    void aCellInsideCostsItsMoveTimesThePreference() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkRequest request = f.request(0, 64, 0, 4, 64, 0);
        WalkPath path = found(weighted(request, region(2, 0, 3, 0, 3.0)));

        assertEquals(1 + 3 + 3 + 1, path.cost(), 1e-9, "two of the four cells entered are inside");
        assertEquals(4.0, path.length(), 1e-9);
    }

    @Test
    void withAnAccessCostTheWeightMultipliesTheMoveNotTheVerdict() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 64, G);
        WalkRequest request = f.request(0, 64, 0, 4, 64, 0).withAccess((x, feetY, z) -> x == 2 ? 5.0 : 0.0);
        WalkPath path = found(weighted(request, region(2, 0, 2, 0, 2.0)));

        assertEquals(4.0 + 1.0 + 5.0, path.cost(), 1e-9);
    }

    /** The same multipliers with a heuristic scale of 0: a plain Dijkstra, the optimum to compare with. */
    private static WalkCellWeights dijkstra(WalkCellWeights weights) {
        return new WalkCellWeights() {
            @Override
            public double multiplier(int x, int feetY, int z) {
                return weights.multiplier(x, feetY, z);
            }

            @Override
            public double minMultiplier() {
                return 0.0;
            }
        };
    }

    @Test
    void theHeuristicStaysAdmissibleTheFoundCostIsTheOptimum() {
        WalkFixture f = hall().layer(65, ".............", ".............", ".............", "....######...");
        List<LandmarkWeights> cases = List.of(
            region(2, 4, 10, 6, 0.25),
            region(3, 0, 9, 3, 2.0),
            LandmarkWeights.of(List.of(
                new LandmarkWeights.Area(1, new LandmarkWeights.UnionArea(List.of(RegionShape.cuboid(4, 60, 3, 8, 80, 8),
                    RegionShape.cuboid(6, 60, 0, 9, 80, 4))), 0.3),
                new LandmarkWeights.Area(2, new LandmarkWeights.RegionArea(RegionShape.cuboid(0, 60, 0, 6, 80, 2)), 0.5),
                new LandmarkWeights.Area(3, new LandmarkWeights.RegionArea(RegionShape.cuboid(8, 60, 0, 12, 80, 6)), 4.0)),
                4.0));
        for (LandmarkWeights weights : cases) {
            WalkRequest request = weighted(f.request(0, 64, 1, 12, 64, 5), weights);
            WalkPath astar = found(request);
            WalkPath optimum = found(request.withWeights(dijkstra(request.weights())));
            assertEquals(optimum.cost(), astar.cost(), 1e-9, "A* with the scaled heuristic finds the optimum: " + weights.areas());
        }
    }

    @Test
    void noWeightsIsTheSearchAsBefore() {
        WalkFixture f = hall().layer(65, ".............", ".............", "....##.......");
        WalkRequest request = straightAcross(f);
        assertSame(WalkCellWeights.NONE, request.weights(), "a request without weights has none");
        WalkPath before = found(request);

        WalkPath none = found(request.withWeights(WalkCellWeights.NONE));
        WalkPath farAway = found(weighted(request, region(200, 200, 210, 210, 2.0)));
        WalkPath nullWeights = found(request.withWeights(null));

        for (WalkPath path : List.of(none, farAway, nullWeights)) {
            assertEquals(before.length(), path.length(), 0.0);
            assertEquals(before.cost(), path.cost(), 0.0);
            assertEquals(before.size(), path.size());
            for (int i = 0; i < before.size(); i++) {
                assertEquals(before.cell(i), path.cell(i));
            }
        }
        assertSame(WalkCellWeights.NONE, weighted(request, region(200, 200, 210, 210, 2.0)).weights(),
            "an area beyond the reach box gives no weights at all");
    }

    @Test
    void theWithMethodsKeepTheWeightsAndTheOldConstructorHasNone() {
        WalkCellWeights weights = region(3, 0, 9, 3, 2.0).within(new int[] {-100, 0, -100, 100, 200, 100});
        WalkRequest request = straightAcross(hall()).withWeights(weights);

        assertSame(weights, request.withAccess(CellAccess.OPEN).weights());
        assertSame(weights, request.withProfile(MovementProfile.PLAYER).weights());
        assertSame(weights, request.withBudget(WalkBudget.DEFAULTS).weights());
        assertSame(weights, request.withGoal(request.goal()).weights());
        WalkRequest old = new WalkRequest(request.terrain(), request.access(), request.profile(), 0.5, 65, 1.5, 12.5, 64,
            1.5, request.goal(), request.budget());
        assertSame(WalkCellWeights.NONE, old.weights());
    }

    @Test
    void theReachBoxHoldsTheStartTheTargetAndTheDetourRoom() {
        WalkRequest request = WalkRequest.toPoint(hall().terrain(), 0.5, 65, 1.5, 40.5, 64, 1.5, 1.0);
        int[] box = request.reachBox();
        double cap = WalkBudget.DEFAULTS.lengthCap(request.straightDistance(), request.heightDifference());

        assertTrue(box[0] <= 0 && box[3] >= 41, "x from the start to the target");
        assertTrue(box[1] <= 65 && box[4] >= 65, "feet y");
        double halfWidth = Math.sqrt(cap * cap / 4 - request.straightDistance() * request.straightDistance() / 4);
        assertTrue(box[2] <= 1.5 - halfWidth && box[5] >= 1.5 + halfWidth, "the ellipse's half width across");
    }
}
