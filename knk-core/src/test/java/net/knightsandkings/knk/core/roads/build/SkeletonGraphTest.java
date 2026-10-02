package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Region;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Anchor;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Chain;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Node;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkeletonGraphTest {
    static final Region EVERYWHERE = new Region(-1000, -1000, 1000, 1000);
    static final BuildParameters PARAMS = BuildParameters.defaults();

    /** Thin the whole fixture (all spans at the listed heights) and extract the graph. */
    static Extraction extract(GridFixture f, int maxX, int maxZ, Region tile, BuildParameters params,
                              List<Anchor> anchors, int... ys) {
        SpanGrid grid = f.spanGrid();
        RoadMask mask = RoadMask.of(grid, RoadMaskTest.allSpans(grid, maxX, maxZ, ys));
        int[] dt = DistanceTransform.compute(mask);
        boolean[] skeleton = Thinning.thin(mask);
        Result result = new SkeletonGraph(mask, skeleton, dt, params, GridFixture.profiles(), tile).extract(anchors);
        return new Extraction(mask, skeleton, result);
    }

    static Extraction extract(GridFixture f, int maxX, int maxZ, int... ys) {
        return extract(f, maxX, maxZ, EVERYWHERE, PARAMS, List.of(), ys);
    }

    record Extraction(RoadMask mask, boolean[] skeleton, Result result) {
        double length(Chain c) {
            double l = 0;
            for (int k = 1; k < c.spans().length; k++) {
                l += mask.distance(c.spans()[k - 1], c.spans()[k]);
            }
            return l;
        }

        long count(RoadNodeKind kind) {
            return result.nodes().stream().filter(n -> n.kind() == kind).count();
        }

        Node node(int x, int y, int z) {
            return result.nodes().stream().filter(n -> n.x() == x && n.y() == y && n.z() == z).findFirst()
                .orElseThrow(() -> new AssertionError("no node at " + x + "," + y + "," + z + " in " + result.nodes()));
        }

        String picture(int y, int maxX, int maxZ) {
            return ThinningTest.render(mask, skeleton, y, 0, maxX, 0, maxZ) + result.nodes() + "\n" + result.chains();
        }
    }

    static GridFixture wideRoad(int length, int width) {
        GridFixture f = new GridFixture();
        for (int z = 0; z < width; z++) {
            f.layer(0, 64, z, "S".repeat(length));
        }
        return f;
    }

    @Test
    void straightOneWidePathIsTwoEndpointsAndOneChain() {
        Extraction e = extract(new GridFixture().layer(64, "GGGGGGGGGG"), 9, 0, 64);
        assertEquals(2, e.result().nodes().size());
        assertEquals(2, e.count(RoadNodeKind.ENDPOINT));
        assertEquals(1, e.result().chains().size());
        assertEquals(9.0, e.length(e.result().chains().get(0)), 1e-9);
        e.node(0, 64, 0);
        e.node(9, 64, 0);
    }

    @Test
    void fiveWideRoadEndsAreRecoveredByEndpointExtension() {
        Extraction e = extract(wideRoad(20, 5), 19, 4, 64);
        String picture = e.picture(64, 19, 4);
        assertEquals(2, e.result().nodes().size(), picture);
        assertEquals(1, e.result().chains().size(), picture);
        e.node(0, 64, 2);
        e.node(19, 64, 2);
        assertEquals(19.0, e.length(e.result().chains().get(0)), 1e-9, picture);
    }

    @Test
    void bentOneWidePathKeepsItsErodedEndCell() {
        GridFixture f = new GridFixture().layer(64,
            "GGGG....",
            "...G....",
            "...GGGG.",
            "......G.",
            "......GG");
        Extraction e = extract(f, 7, 4, 64);
        String picture = e.picture(64, 7, 4);
        assertEquals(2, e.result().nodes().size(), picture);
        e.node(0, 64, 0);
        e.node(7, 64, 4);
        assertEquals(1, e.result().chains().size(), picture);
    }

    @Test
    void tOfFiveWideRoadsHasOneJunctionAndThreeArms() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 15; z++) {
            f.layer(0, 64, z, z < 5 ? "S".repeat(25) : ".".repeat(10) + "SSSSS" + ".".repeat(10));
        }
        Extraction e = extract(f, 24, 14, 64);
        String picture = e.picture(64, 24, 14);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(3, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(3, e.result().chains().size(), picture);
        Node junction = e.result().nodes().stream().filter(n -> n.kind() == RoadNodeKind.JUNCTION).findFirst().orElseThrow();
        assertTrue(Math.abs(junction.x() - 12) <= 1 && junction.z() >= 2 && junction.z() <= 3, "junction near the crossing: " + junction);
        for (Chain c : e.result().chains()) {
            assertTrue(c.from() == junction.id() || c.to() == junction.id(), "every arm touches the junction");
        }
        e.node(0, 64, 2);
        e.node(24, 64, 2);
        e.node(12, 64, 14);
    }

    @Test
    void crossOfFiveWideRoadsHasOneJunctionAndFourArms() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 25; z++) {
            f.layer(0, 64, z, z >= 10 && z < 15 ? "S".repeat(25) : ".".repeat(10) + "SSSSS" + ".".repeat(10));
        }
        Extraction e = extract(f, 24, 24, 64);
        String picture = e.picture(64, 24, 24);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(4, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(4, e.result().chains().size(), picture);
        e.node(12, 64, 12);
        for (Chain c : e.result().chains()) {
            assertEquals(11.0, e.length(c), 1.0, picture);
        }
    }

    @Test
    void fiveOneWidePathsMeetingCloseTogetherAreOneJunction() {
        // W, E, N, S arms from (10,10) plus a staircase NE arm branching off the E arm one cell away.
        // The NE arm is a 4-connected staircase (a diagonal of corner-touching cells is not walkable).
        GridFixture f = new GridFixture();
        f.layer(64,
            "..........G.........",
            "..........G.........",
            "..........G.........",
            "..........G.......G.",
            "..........G......GG.",
            "..........G.....GG..",
            "..........G....GG...",
            "..........G...GG....",
            "..........G..GG.....",
            "..........GGGG......",
            "GGGGGGGGGGGGGGGGGGGG",
            "..........G.........",
            "..........G.........",
            "..........G.........",
            "..........G.........",
            "..........G.........");
        Extraction e = extract(f, 19, 15, 64);
        String picture = e.picture(64, 19, 15);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(5, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(5, e.result().chains().size(), picture);
    }

    @Test
    void junctionsFurtherApartThanTheRadiusStaySeparate() {
        // Two T junctions on a 1-wide path, 8 apart, with stems longer than the spur threshold.
        GridFixture f = new GridFixture().layer(64,
            "GGGGGGGGGGGGGGGGGG",
            "....G.......G.....",
            "....G.......G.....",
            "....G.......G.....",
            "....G.......G.....",
            "....G.......G.....",
            "....G.......G.....");
        Extraction e = extract(f, 17, 6, 64);
        String picture = e.picture(64, 17, 6);
        assertEquals(2, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(4, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(5, e.result().chains().size(), picture);
    }

    @Test
    void plazaCollapsesToOneJunctionJoinedToEveryExit() {
        GridFixture f = new GridFixture();
        for (int z = 0; z < 39; z++) {
            String row;
            if (z < 12 || z >= 27) row = ".".repeat(18) + "SSS" + ".".repeat(18);
            else if (z >= 18 && z <= 20) row = "S".repeat(39);
            else row = ".".repeat(12) + "S".repeat(15) + ".".repeat(12);
            f.layer(0, 64, z, row);
        }
        Extraction e = extract(f, 38, 38, 64);
        String picture = e.picture(64, 38, 38);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(4, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(4, e.result().chains().size(), picture);
        Node plaza = e.node(19, 64, 19);
        assertEquals(RoadNodeKind.JUNCTION, plaza.kind());
        for (Chain c : e.result().chains()) {
            assertTrue(c.from() == plaza.id() || c.to() == plaza.id(), picture);
        }
    }

    /**
     * Smoke test fix plan 5.5 item 5 (finding C): a ~21-wide square with an irregular outline - chipped
     * corners, an alcove, two bumps - a lamp post near its west edge and a planter near a corner, and
     * three 5-wide exits. The skeleton forks toward every corner and bump in the 3-4 block band along
     * the edge, outside the plaza's wide core; all of it must be the one plaza junction.
     */
    static GridFixture irregularPlaza() {
        return new GridFixture().layer(64,
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS...SSS.................",
            "..................SSSSS...SSS.................",
            "............SSSSSSSSSSSSSSSSSSS...............",
            "...........SSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSS..SS...............",
            "........SSSSSSSSSSSSSSSSSSS..SS...............",
            "........SSSSSSSSSSSSSSSSSSSSSSS...............",
            "........SSSSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
            "..........SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
            "..........SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
            "..........SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
            "..........SS.SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSSS...............",
            "..........SSSSSSSSSSSSSSSSSSSS................",
            "..........SSSSSSSSSSSSSSSSSSS.................",
            "............SS....SSSSS.......................",
            "............SS....SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................",
            "..................SSSSS.......................");
    }

    @Test
    void anIrregularPlazaWithObstaclesIsOneJunction() {
        Extraction e = extract(irregularPlaza(), 45, 45, 64);
        String picture = e.picture(64, 45, 45);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(3, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(3, e.result().chains().size(), picture);
        Node plaza = e.result().nodes().stream().filter(n -> n.kind() == RoadNodeKind.JUNCTION).findFirst().orElseThrow();
        assertTrue(plaza.x() >= 15 && plaza.x() <= 25 && plaza.z() >= 15 && plaza.z() <= 25, "near the square's middle: " + plaza);
        for (Chain c : e.result().chains()) {
            assertTrue(c.from() == plaza.id() || c.to() == plaza.id(), picture);
        }
    }

    @Test
    void withoutPlazaGrowthTheIrregularPlazaStillFragments() {
        // The tunable is what fixes it (and can be tuned per server): the strict core alone forks.
        Extraction e = extract(irregularPlaza(), 45, 45, EVERYWHERE, PARAMS.withPlazaGrowth(0).withGraphRules(0, 0), List.of(), 64);
        assertTrue(e.count(RoadNodeKind.JUNCTION) > 1, e.picture(64, 45, 45));
    }

    @Test
    void aForkRightAtAPlazasEdgeMergesIntoThePlazaJunction() {
        // Fix plan 5.5 item 5: a junction candidate within junction-cluster-radius of a plaza (reached
        // along the skeleton, not through the plaza) is part of the plaza's junction, not a second one.
        GridFixture f = new GridFixture().layer(64,
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSSGGGGGGGGGGGGGG..",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            ".....SSSSSSSSSSSSSSS..........",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................",
            "...........SSS................");
        BuildParameters params = PARAMS.withPlazaGrowth(0).withGraphRules(5, PARAMS.minSpurLength());
        Extraction e = extract(f, 29, 39, EVERYWHERE, params, List.of(), 64);
        String picture = e.picture(64, 29, 39);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(3, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(3, e.result().chains().size(), picture);

        Extraction separate = extract(f, 29, 39, EVERYWHERE, params.withGraphRules(1, PARAMS.minSpurLength()), List.of(), 64);
        assertEquals(2, separate.count(RoadNodeKind.JUNCTION), "farther than the radius: its own junction\n"
            + separate.picture(64, 29, 39));
    }

    @Test
    void shortSpursAtAJunctionArePrunedLongArmsStay() {
        // A 1-wide path with a 2-cell stub (spur) and a 6-cell branch; every real arm is longer
        // than minSpurLength (4) measured from the junction.
        GridFixture f = new GridFixture().layer(64,
            "....G...........",
            "....G...........",
            "GGGGGGGGGGGGGGGG",
            "........G.......",
            "........G.......",
            "........G.......",
            "........G.......",
            "........G.......",
            "........G.......");
        Extraction e = extract(f, 15, 8, 64);
        String picture = e.picture(64, 15, 8);
        assertEquals(1, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(3, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(3, e.result().chains().size(), picture);
        e.node(8, 64, 8);
        assertTrue(e.result().nodes().stream().noneMatch(n -> n.z() == 0), "the stub is gone" + picture);
    }

    @Test
    void aJunctionLeftWithTwoChainsDissolvesIntoOne() {
        // A straight path with one 2-cell stub: after pruning the stub the path is a single chain.
        GridFixture f = new GridFixture().layer(64,
            "GGGGGGGGGGGGGG",
            "......G.......",
            "......G.......");
        Extraction e = extract(f, 13, 2, 64);
        String picture = e.picture(64, 13, 2);
        assertEquals(0, e.count(RoadNodeKind.JUNCTION), picture);
        assertEquals(2, e.count(RoadNodeKind.ENDPOINT), picture);
        assertEquals(1, e.result().chains().size(), picture);
        assertEquals(13.0, e.length(e.result().chains().get(0)), 1e-9,
            "the merged chain runs through the road cell thinning removed, not through the stub" + picture);
    }

    @Test
    void tinyIsolatedBlobsProduceNothing() {
        Extraction e = extract(new GridFixture().layer(64, "GG.......GGGGGGGG"), 16, 0, 64);
        assertEquals(2, e.result().nodes().size(), "only the long piece");
        assertEquals(1, e.result().chains().size());
        assertEquals(0, extract(new GridFixture().layer(64, "S"), 0, 0, 64).result().nodes().size());
    }

    @Test
    void anchorsSplitChainsAndTakeOverJunctions() {
        GridFixture f = new GridFixture().layer(64, "GGGGGGGGGGGGGGGGGGGG");
        Extraction e = extract(f, 19, 0, EVERYWHERE, PARAMS, List.of(new Anchor(77, 10, 66, 1)), 64);
        assertEquals(3, e.result().nodes().size());
        Node anchor = e.node(10, 66, 1);
        assertEquals(RoadNodeKind.ANCHOR, anchor.kind());
        assertEquals(OptionalInt.of(77), anchor.anchorId());
        assertEquals(2, e.result().chains().size());
        assertTrue(e.result().warnings().isEmpty());

        // On a junction: the junction becomes the anchor.
        GridFixture t = new GridFixture().layer(64,
            "GGGGGGGGGGGGGGGGGG",
            "........G.........",
            "........G.........",
            "........G.........",
            "........G.........",
            "........G.........");
        Extraction et = extract(t, 17, 5, EVERYWHERE, PARAMS, List.of(new Anchor(5, 8, 64, 0)), 64);
        assertEquals(1, et.count(RoadNodeKind.ANCHOR), et.picture(64, 17, 5));
        assertEquals(0, et.count(RoadNodeKind.JUNCTION));
        assertEquals(3, et.result().chains().size());
    }

    @Test
    void anchorOffTheRoadIsAWarning() {
        GridFixture f = new GridFixture().layer(64, "GGGGGGGGGG");
        Extraction e = extract(f, 9, 0, EVERYWHERE, PARAMS, List.of(new Anchor(9, 5, 64, 6)), 64);
        assertEquals(2, e.result().nodes().size());
        assertEquals(1, e.result().warnings().size());
        assertTrue(e.result().warnings().get(0).message().startsWith(SkeletonGraph.WARN_ANCHOR_OFF_ROAD));
        assertEquals(6, e.result().warnings().get(0).z());
    }

    @Test
    void ringRoadGetsThreeNodesSoEveryEdgeHasItsOwnPair() {
        GridFixture f = new GridFixture().layer(64,
            "GGGGGGGGGG",
            "G........G",
            "G........G",
            "G........G",
            "G........G",
            "GGGGGGGGGG");
        Extraction e = extract(f, 9, 5, 64);
        String picture = e.picture(64, 9, 5);
        assertEquals(3, e.result().nodes().size(), picture);
        assertEquals(3, e.result().chains().size(), picture);
        double total = 0;
        for (Chain c : e.result().chains()) {
            total += e.length(c);
        }
        assertEquals(28 - 4 * (2 - Math.sqrt(2)), total, 1e-6, "28 cells with four cut corners" + picture);
    }

    @Test
    void parallelChainsBetweenTwoJunctionsAreSplit() {
        // A path that forks around an obstacle and rejoins: two chains with the same node pair.
        GridFixture f = new GridFixture().layer(64,
            "......GGGGGG......",
            "......G....G......",
            "GGGGGGG....GGGGGGG",
            "......G....G......",
            "......GGGGGG......");
        Extraction e = extract(f, 17, 4, 64);
        String picture = e.picture(64, 17, 4);
        assertEquals(2, e.count(RoadNodeKind.ENDPOINT), picture);
        long junctions = e.count(RoadNodeKind.JUNCTION);
        assertTrue(junctions >= 3, "two forks plus at least one splitting node" + picture);
        java.util.Set<String> pairs = new java.util.HashSet<>();
        for (Chain c : e.result().chains()) {
            String pair = Math.min(c.from(), c.to()) + "-" + Math.max(c.from(), c.to());
            assertTrue(pairs.add(pair), "duplicate pair " + pair + picture);
            assertTrue(c.from() != c.to(), "no loops" + picture);
        }
    }

    @Test
    void chainsAreCutAtTheTileBorderIntoBoundaryNodes() {
        GridFixture f = new GridFixture().layer(-8, 64, 5, "G".repeat(40)); // x -8..31 at z 5
        Region tile = Region.tile(0, 0, 16);
        SpanGrid grid = f.spanGrid();
        java.util.List<Long> keys = new java.util.ArrayList<>();
        for (int x = -8; x < 32; x++) keys.add(net.knightsandkings.knk.core.util.BlockKey.pack(x, 64, 5));
        RoadMask mask = RoadMask.of(grid, keys.stream().mapToLong(Long::longValue).toArray());
        int[] dt = DistanceTransform.compute(mask);
        boolean[] skeleton = Thinning.thin(mask);
        Result r = new SkeletonGraph(mask, skeleton, dt, PARAMS, GridFixture.profiles(), tile).extract(List.of());

        assertEquals(2, r.nodes().size(), r.nodes().toString());
        assertEquals(2, r.nodes().stream().filter(n -> n.kind() == RoadNodeKind.BOUNDARY).count());
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 0 && n.z() == 5));
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 15 && n.z() == 5));
        assertEquals(1, r.chains().size());
        assertEquals(16, r.chains().get(0).spans().length);
    }

    @Test
    void aPlazaStraddlingTheTileBorderGetsItsBoundaryNodeOnTheBorder() {
        // Smoke test finding B: a plaza whose centre lies west of the tile (x -9..3, centre x -3) owns
        // skeleton spans inside the tile; the chain leaving it east starts at such a member span. The
        // Boundary node must sit on the border column x = 0 (the API rejects one anywhere else).
        GridFixture f = new GridFixture();
        for (int z = 2; z <= 14; z++) {
            f.layer(-9, 64, z, "S".repeat(13));          // plaza x -9..3
        }
        f.layer(4, 64, 7, "S".repeat(26));                // 3-wide road east x 4..29, z 7..9
        f.layer(4, 64, 8, "S".repeat(26));
        f.layer(4, 64, 9, "S".repeat(26));
        Region tile = Region.tile(0, 0, 16);
        SpanGrid grid = f.spanGrid();
        java.util.List<Long> keys = new java.util.ArrayList<>();
        for (int x = -9; x <= 29; x++) {
            for (int z = 0; z <= 16; z++) {
                if (grid.isSpan(x, 64, z)) keys.add(net.knightsandkings.knk.core.util.BlockKey.pack(x, 64, z));
            }
        }
        RoadMask mask = RoadMask.of(grid, keys.stream().mapToLong(Long::longValue).toArray());
        int[] dt = DistanceTransform.compute(mask);
        boolean[] skeleton = Thinning.thin(mask);
        Result r = new SkeletonGraph(mask, skeleton, dt, PARAMS, GridFixture.profiles(), tile).extract(List.of());

        String picture = r.nodes() + "\n" + r.chains();
        List<Node> boundaries = r.nodes().stream().filter(n -> n.kind() == RoadNodeKind.BOUNDARY).toList();
        assertEquals(2, boundaries.size(), picture);
        for (Node b : boundaries) {
            assertTrue(b.x() == tile.minX() || b.x() == tile.maxX() || b.z() == tile.minZ() || b.z() == tile.maxZ(),
                "Boundary node off the tile border: " + b + "\n" + picture);
        }
        Node ours = boundaries.stream().filter(n -> n.x() == 0).findFirst().orElseThrow(() -> new AssertionError(picture));
        for (Chain c : r.chains()) {
            for (int k = 1; k < c.spans().length; k++) {
                assertTrue(mask.distance(c.spans()[k - 1], c.spans()[k]) < 1.5, "chain steps are neighbours: " + picture);
            }
        }

        // The tile west of it owns the plaza junction: its chain toward this tile ends in a Boundary
        // node on x = -1 next to ours, so the API's stitch rule (Chebyshev <= 1, |dy| <= 1) joins them.
        Region west = Region.tile(-1, 0, 16);
        int[] dt2 = DistanceTransform.compute(mask);
        boolean[] skeleton2 = Thinning.thin(mask);
        Result w = new SkeletonGraph(mask, skeleton2, dt2, PARAMS, GridFixture.profiles(), west).extract(List.of());
        String westPicture = w.nodes() + "\n" + w.chains();
        assertEquals(1, w.nodes().stream().filter(n -> n.kind() == RoadNodeKind.JUNCTION).count(), westPicture);
        Node theirs = w.nodes().stream().filter(n -> n.kind() == RoadNodeKind.BOUNDARY && n.x() == -1).findFirst()
            .orElseThrow(() -> new AssertionError("no Boundary on x = -1: " + westPicture));
        assertTrue(Math.abs(theirs.z() - ours.z()) <= 1 && Math.abs(theirs.y() - ours.y()) <= 1,
            "stitchable: " + theirs + " / " + ours);
    }

    @Test
    void anEndpointOnTheBorderCellWithTheRoadContinuingIsABoundaryNode() {
        // Road from x=3 to x=20 at z=2; tile 0..15: the inside part ends at x=15 → Boundary, x=3 → Endpoint.
        GridFixture f = new GridFixture().layer(3, 64, 2, "G".repeat(18));
        Extraction e = extract(f, 25, 5, Region.tile(0, 0, 16), PARAMS, List.of(), 64);
        assertEquals(1, e.count(RoadNodeKind.ENDPOINT));
        assertEquals(1, e.count(RoadNodeKind.BOUNDARY));
        assertEquals(1, e.result().chains().size());
        e.node(3, 64, 2);
        e.node(15, 64, 2);
    }

    @Test
    void everythingOutsideTheTileIsDropped() {
        GridFixture f = new GridFixture().layer(20, 64, 20, "GGGGGGGG");
        Extraction e = extract(f, 30, 30, Region.tile(0, 0, 16), PARAMS, List.of(), 64);
        assertEquals(0, e.result().nodes().size());
        assertEquals(0, e.result().chains().size());
    }

    @Test
    void stackedRoadsGiveSeparateChains() {
        GridFixture f = new GridFixture().layer(64, "GGGGGGGGGG").layer(70, "GGGGGGGGGG");
        Extraction e = extract(f, 9, 0, 64, 70);
        assertEquals(4, e.count(RoadNodeKind.ENDPOINT));
        assertEquals(2, e.result().chains().size());
    }
}
