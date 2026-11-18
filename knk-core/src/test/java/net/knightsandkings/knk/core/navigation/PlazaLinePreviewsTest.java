package net.knightsandkings.knk.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.GridWorld;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The staff preview's passes (off-road navigation phase 7 step 1, PandiO/knk-workspace#712): a named node gives every
 * pair of its roads, the route gives one pass per junction, nodes are found by id or name, and the chat line says what
 * came out against the chord and the right angle. On a plaza of the NPC scene (15 × 15, junction at block 15 15,
 * roads west, north and east).
 */
class PlazaLinePreviewsTest {

    private static final String W = GridWorld.W;
    private static final PlazaCurve.Options OPTIONS = PlazaCurve.Options.defaults(4, 0.8);

    private final GridWorld world = new GridWorld();

    private void plaza() {
        world.walls(7, 7, 23, 7).walls(7, 23, 23, 23).walls(7, 7, 7, 23).walls(23, 7, 23, 23);
        for (int a = 14; a <= 16; a++) {
            world.walls.remove(GridWorld.key(a, 7));
            world.walls.remove(GridWorld.key(7, a));
            world.walls.remove(GridWorld.key(23, a));
        }
        world.walls(13, -6, 13, 6).walls(17, -6, 17, 6).walls(-6, 13, 6, 13).walls(-6, 17, 6, 17);
        world.walls(24, 13, 36, 13).walls(24, 17, 36, 17);
    }

    /** The plaza node 1 at block (15, 64, 15) with roads to the west (2), north (3) and east (4). */
    private static RoadNetworkSnapshot snapshot(int plazaRadius, String name) {
        RoadNode plaza = new RoadNode(1, 15, 64, 15, plazaRadius > 0 ? RoadNodeKind.ANCHOR : RoadNodeKind.JUNCTION, name, 0,
            false, plazaRadius);
        return RoadNetworkSnapshot.builder(W)
            .addNode(plaza)
            .addNode(new RoadNode(2, -5, 64, 15, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(3, 15, 64, -5, RoadNodeKind.ENDPOINT, "North End", 0))
            .addNode(new RoadNode(4, 35, 64, 15, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(5, 100, 64, 100, RoadNodeKind.JUNCTION, name, 0))
            .addStreet(new RoadNetworkSnapshot.Street(7, "West Street"))
            .addEdge(edge(10, 2, 1, straight(-5, 15, 15, 15), 7))
            .addEdge(edge(11, 1, 3, straight(15, 15, 15, -5), -1))
            .addEdge(edge(12, 4, 1, straight(35, 15, 15, 15), -1))
            .build();
    }

    private static List<int[]> straight(int x1, int z1, int x2, int z2) {
        List<int[]> out = new ArrayList<>();
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            out.add(new int[] {x1 + Integer.signum(x2 - x1) * i, 64, z1 + Integer.signum(z2 - z1) * i});
        }
        return out;
    }

    private static RoadEdge edge(int id, int from, int to, List<int[]> geometry, int street) {
        return new RoadEdge(id, from, to, geometry, geometry.size() - 1, 3.0, OptionalInt.empty(),
            street < 0 ? OptionalInt.empty() : OptionalInt.of(street), 1.0, Set.of(), List.of(), List.of(), List.of(),
            RoadEdgeSource.DETECTED, false);
    }

    @Test
    void roadPointsBecomeFeetOnTheBlockCentre() {
        List<NavPoint> feet = PlazaLinePreviews.feetOf(W, List.of(new double[] {3, 63, -4}));
        assertEquals(new NavPoint(W, 3.5, 64, -3.5), feet.get(0));
    }

    @Test
    void aNodeIsFoundByIdOrNameTheNearestOfTwoNamesakes() {
        RoadNetworkSnapshot s = snapshot(7, "Brink");
        assertEquals(1, PlazaLinePreviews.findNode(s, "1", 0, 0).orElseThrow().id());
        assertEquals(1, PlazaLinePreviews.findNode(s, "#1", 0, 0).orElseThrow().id());
        assertEquals(1, PlazaLinePreviews.findNode(s, "brink", 10, 10).orElseThrow().id());
        assertEquals(5, PlazaLinePreviews.findNode(s, "BRINK", 90, 90).orElseThrow().id());
        assertEquals(3, PlazaLinePreviews.findNode(s, " North End ", 0, 0).orElseThrow().id());
        assertTrue(PlazaLinePreviews.findNode(s, "Cinix", 0, 0).isEmpty());
        assertTrue(PlazaLinePreviews.findNode(s, "", 0, 0).isEmpty());
        assertTrue(PlazaLinePreviews.findNode(s, "99", 0, 0).isEmpty());
    }

    @Test
    void aNodeWithThreeRoadsGivesThreePairsEachThroughTheNode() {
        RoadNetworkSnapshot s = snapshot(7, "Brink");
        List<PlazaLinePreviews.Pass> passes = PlazaLinePreviews.throughNode(s, s.requireNode(1), 12);

        assertEquals(3, passes.size());
        assertEquals("Brink (#1): West Street (edge #10) → edge #11", passes.get(0).label());
        assertEquals("Brink (#1): West Street (edge #10) → edge #12", passes.get(1).label());
        assertEquals("Brink (#1): edge #11 → edge #12", passes.get(2).label());
        for (PlazaLinePreviews.Pass p : passes) {
            assertEquals(new PlazaCrossing(new NavPoint(W, 15, 64, 15), 7), p.plaza());
            // 12 blocks in, 12 out, the node once
            assertEquals(25, p.feet().size());
            assertEquals(new NavPoint(W, 15.5, 65, 15.5), p.feet().get(12));
        }
        // the first pass comes in from the west and leaves to the north
        assertEquals(3.5, passes.get(0).feet().get(0).x(), 1e-9);
        assertEquals(3.5, passes.get(0).feet().get(24).z(), 1e-9);
        // an edge stored towards the node (12: from 4 to 1) and one away from it (11) both end at the node
        assertEquals(27.5, passes.get(2).feet().get(24).x(), 1e-9);
    }

    @Test
    void aShortPieceOfRoadIsFollowedOnThroughASplitNode() {
        // the west road stored as two pieces: node 1 to the split node 8 at x 11, then on to the dead end 2
        RoadNetworkSnapshot s = RoadNetworkSnapshot.builder(W)
            .addNode(new RoadNode(1, 15, 64, 15, RoadNodeKind.JUNCTION, "Brink", 0))
            .addNode(new RoadNode(2, -5, 64, 15, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(3, 15, 64, -5, RoadNodeKind.ENDPOINT, null, 0))
            .addNode(new RoadNode(8, 11, 64, 15, RoadNodeKind.SPLIT, null, 0))
            .addEdge(edge(20, 1, 8, straight(15, 15, 11, 15), -1))
            .addEdge(edge(21, 8, 2, straight(11, 15, -5, 15), -1))
            .addEdge(edge(11, 1, 3, straight(15, 15, 15, -5), -1))
            .build();
        List<PlazaLinePreviews.Pass> passes = PlazaLinePreviews.throughNode(s, s.requireNode(1), 12);

        assertEquals(1, passes.size());
        assertEquals(25, passes.get(0).feet().size(), "12 blocks in along both pieces, 12 out");
        assertEquals(3.5, passes.get(0).feet().get(0).x(), 1e-9);
    }

    @Test
    void aDeadEndHasNoPairs() {
        RoadNetworkSnapshot s = snapshot(7, "Brink");
        assertTrue(PlazaLinePreviews.throughNode(s, s.requireNode(2), 12).isEmpty());
    }

    @Test
    void theRoutesJunctionsAreOnePassEachOverTheWholeRoute() {
        List<double[]> polyline = List.of(new double[] {-5, 63, 15}, new double[] {15, 63, 15}, new double[] {15, 63, -5});
        List<PlazaCrossing> crossings = List.of(new PlazaCrossing(new NavPoint(W, 15, 63, 15), 7));
        List<PlazaLinePreviews.Pass> passes = PlazaLinePreviews.alongRoute(W, polyline, crossings, c -> "the plaza");

        assertEquals(1, passes.size());
        assertEquals("the plaza", passes.get(0).label());
        assertEquals(3, passes.get(0).feet().size());
    }

    @Test
    void eachPassIsWorkedOutAndDescribed() {
        plaza();
        RoadNetworkSnapshot s = snapshot(7, "Brink");
        List<PlazaLinePreviews.Line> lines = PlazaLinePreviews.compute(world,
            PlazaLinePreviews.throughNode(s, s.requireNode(1), 20), OPTIONS);

        assertEquals(3, lines.size());
        PlazaLinePreviews.Line westNorth = lines.get(0);
        assertEquals(PlazaCurve.Kind.CURVE, westNorth.result().kind(), westNorth.result().curveFailure());
        String text = PlazaLinePreviews.describe(westNorth);
        assertTrue(text.startsWith("Brink (#1): West Street (edge #10) → edge #11: curve "), text);
        assertTrue(text.contains("chord 9.9, right angle 14.0, "), text);
        assertTrue(text.contains("clearance at least"), text);
        // west to east is the straight road itself: as long as the right angle
        PlazaLinePreviews.Line westEast = lines.get(1);
        assertTrue(PlazaLinePreviews.describe(westEast).contains("right angle 14.0, 0 %"),
            PlazaLinePreviews.describe(westEast));
    }

    @Test
    void theOtherKindsSayWhatHappened() {
        PlazaLinePreviews.Pass pass = new PlazaLinePreviews.Pass("Brink", List.of(),
            new PlazaCrossing(new NavPoint(W, 15, 63, 15), 0));
        NavPoint c = pass.plaza().centre();
        assertEquals("Brink: a street junction (open floor no wider than the corridor), no plaza line",
            PlazaLinePreviews.describe(new PlazaLinePreviews.Line(pass, new PlazaCurve.Result(PlazaCurve.Kind.STREET,
                List.of(), List.of(), c, 0, -1, -1, 0, 0, 0, 0, null, 0))));
        assertTrue(PlazaLinePreviews.describe(new PlazaLinePreviews.Line(pass, new PlazaCurve.Result(
            PlazaCurve.Kind.NOT_CROSSED, List.of(), List.of(), c, 0, -1, -1, 0, 0, 0, 0, null, 0))).contains("does not cross"));
        String none = PlazaLinePreviews.describe(new PlazaLinePreviews.Line(pass, new PlazaCurve.Result(
            PlazaCurve.Kind.NONE, List.of(), List.of(), c, 7, 13, 27, 0, 9.9, 14, 0, "a step of more than a block at 9.0 9.0",
            4096)));
        assertTrue(none.contains("keeps its bend (right angle 14.0)") && none.contains("a step of more than a block"), none);
        String guided = PlazaLinePreviews.describe(new PlazaLinePreviews.Line(pass, new PlazaCurve.Result(
            PlazaCurve.Kind.GUIDED, List.of(), List.of(), c, 7, 13, 27, 11.2, 9.9, 14, 0.81, "0.40 from an obstacle", 300)));
        assertTrue(guided.startsWith("Brink: A* line 11.2 blocks (chord 9.9, right angle 14.0, -20 %)"), guided);
        assertTrue(guided.endsWith("the curve did not fit: 0.40 from an obstacle"), guided);
    }
}
