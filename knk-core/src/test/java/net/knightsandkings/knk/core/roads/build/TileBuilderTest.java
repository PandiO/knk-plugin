package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.MaskBuilder.Seed;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousEdge;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousGraph;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousNode;
import net.knightsandkings.knk.core.roads.build.ProfileSet.Profile;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph.Anchor;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.Edge;
import net.knightsandkings.knk.core.roads.build.TileBuildResult.Node;
import net.knightsandkings.knk.core.roads.build.TileBuilder.TileRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan's golden tests (Phase 2c "Test fixtures"): every scenario asserts node kinds and count,
 * edge count, edge lengths ±1 and that no stray spur is left. Tiles are 64 blocks with a 8-block
 * margin unless a test says otherwise; all fixtures fit in tile (0, 0).
 */
class TileBuilderTest {
    private static final BuildParameters PARAMS = BuildParameters.defaults().withTile(64, 8);
    private final TileBuilder builder = new TileBuilder();

    private static TileRequest request(GridFixture f, Seed... seeds) {
        return request(f, PARAMS, GridFixture.profiles(), List.of(), PreviousGraph.EMPTY, seeds);
    }

    private static TileRequest request(GridFixture f, BuildParameters params, ProfileSet profiles, List<Anchor> anchors,
                                       PreviousGraph previous, Seed... seeds) {
        return new TileRequest("world", 0, 0, params, List.of(seeds), profiles, f, anchors, previous);
    }

    private static GridFixture wideRoad(int x0, int z0, int length, int width, int y) {
        GridFixture f = new GridFixture();
        for (int z = 0; z < width; z++) {
            f.layer(x0, y, z0 + z, "S".repeat(length));
        }
        return f;
    }

    private static Node onlyNode(TileBuildResult r, RoadNodeKind kind) {
        List<Node> nodes = r.nodes(kind);
        assertEquals(1, nodes.size(), "one " + kind + " in " + r.nodes());
        return nodes.get(0);
    }

    private static Edge onlyEdge(TileBuildResult r) {
        assertEquals(1, r.edges().size(), "one edge in " + r.edges());
        return r.edges().get(0);
    }

    /** Every edge satisfies the API contract: ends on its nodes, length ≥ chord, no cross-tile edges. */
    private static void assertContract(TileBuildResult r, TileRequest req) {
        Set<String> keys = new java.util.HashSet<>();
        for (Node n : r.nodes()) {
            assertTrue(keys.add(n.key()), "unique key " + n.key());
            assertFalse(n.key().startsWith("id:"));
            assertTrue(req.tile().contains(n.x(), n.z()), "node inside the tile: " + n);
            if (n.kind() == RoadNodeKind.BOUNDARY) {
                assertTrue(n.x() == req.tile().minX() || n.x() == req.tile().maxX()
                    || n.z() == req.tile().minZ() || n.z() == req.tile().maxZ(), "boundary node on a border cell: " + n);
            }
        }
        Set<String> pairs = new java.util.HashSet<>();
        for (Edge e : r.edges()) {
            Node from = r.node(e.fromKey()).orElseThrow();
            Node to = r.node(e.toKey()).orElseThrow();
            assertNotEquals(from.key(), to.key(), "no loops");
            assertTrue(pairs.add(from.key().compareTo(to.key()) < 0 ? from.key() + "|" + to.key() : to.key() + "|" + from.key()),
                "unique node pair " + e.fromKey() + "-" + e.toKey());
            int[] first = e.geometry().get(0);
            int[] last = e.geometry().get(e.geometry().size() - 1);
            assertEquals(0.0, dist(first, from), 1e-9, "geometry starts on its node");
            assertEquals(0.0, dist(last, to), 1e-9, "geometry ends on its node");
            assertTrue(e.length() >= e.chord() - 1e-9, "length >= straight line");
            assertTrue(e.avgWidth() >= 1);
            assertEquals(List.of(), e.domainIds());
            assertEquals(List.of(), e.regionIds());
        }
        assertEquals(TileBuilder.BUILDER_VERSION, r.builderVersion());
    }

    private static double dist(int[] p, Node n) {
        double dx = p[0] - n.x();
        double dy = p[1] - n.y();
        double dz = p[2] - n.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String describe(TileBuildResult r) {
        StringBuilder sb = new StringBuilder("\nnodes:\n");
        for (Node n : r.nodes()) sb.append("  ").append(n).append('\n');
        sb.append("edges:\n");
        for (Edge e : r.edges()) {
            sb.append("  ").append(e.fromKey()).append("->").append(e.toKey()).append(" len=")
                .append(String.format("%.2f", e.length())).append(" width=").append(String.format("%.1f", e.avgWidth()))
                .append(" profile=").append(e.profileId()).append(" gates=").append(e.gateDoorIds())
                .append(" geometry=").append(e.geometry().size()).append(" points\n");
        }
        sb.append("warnings: ").append(r.warningTexts()).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void meanderingOneWidePath() {
        GridFixture f = new GridFixture().layer(2, 64, 2,
            "GGGGGG......",
            ".....G......",
            ".....G......",
            ".....GGGGGG.",
            "..........G.",
            "..........G.",
            "..........GG");
        TileRequest req = request(f, new Seed(2, 65, 2));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes().size(), d);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        Edge e = onlyEdge(r);
        // 18 cells; three L-corners cut into diagonals: 17 - 3·(2 - √2)
        assertEquals(17 - 3 * (2 - Math.sqrt(2)), e.length(), 1.0, d);
        assertEquals(OptionalInt.of(2), e.profileId(), "gravel path" + d);
        assertEquals(1.0, e.avgWidth(), 1e-9, d);
        assertTrue(e.geometry().size() >= 4 && e.geometry().size() <= 8, "RDP keeps the bends: " + d);
        assertEquals(18, r.cellCount());
        assertEquals(1, r.levelCount());
        assertTrue(r.warnings().isEmpty(), d);
    }

    @Test
    void fiveWideRoadBecomesASingleCentreline() {
        GridFixture f = wideRoad(4, 10, 30, 5, 64);
        TileRequest req = request(f, new Seed(10, 65, 12));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(2, r.nodes().size(), d);
        Edge e = onlyEdge(r);
        assertEquals(29.0, e.length(), 1.0, d);
        assertEquals(2, e.geometry().size(), "a straight line" + d);
        assertEquals(12, e.geometry().get(0)[2], "middle row");
        assertEquals(5.0, e.avgWidth(), 0.5, d);
        assertEquals(OptionalInt.of(1), e.profileId(), d);
        assertEquals(150, r.cellCount());
    }

    @Test
    void tJunction() {
        GridFixture f = wideRoad(4, 4, 40, 5, 64);
        for (int z = 9; z < 30; z++) f.layer(21, 64, z, "SSSSS");
        TileRequest req = request(f, new Seed(5, 65, 6));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node j = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(3, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(3, r.edges().size(), d);
        assertTrue(Math.abs(j.x() - 23) <= 1 && j.z() >= 6 && j.z() <= 7, "junction at the crossing: " + j + d);
        for (Edge e : r.edges()) {
            assertTrue(e.fromKey().equals(j.key()) || e.toKey().equals(j.key()), "every edge touches the junction" + d);
        }
        Map<String, Double> byFarEnd = new HashMap<>();
        for (Edge e : r.edges()) {
            Node far = r.node(e.fromKey().equals(j.key()) ? e.toKey() : e.fromKey()).orElseThrow();
            byFarEnd.put(far.x() + "," + far.z(), e.length());
        }
        assertEquals(19.0, byFarEnd.get("4,6"), 1.5, "west arm" + d);
        assertEquals(20.0, byFarEnd.get("43,6"), 1.5, "east arm" + d);
        assertEquals(23.0, byFarEnd.get("23,29"), 1.5, "south arm" + d);
    }

    @Test
    void xJunction() {
        GridFixture f = wideRoad(2, 22, 50, 5, 64);
        for (int z = 2; z < 52; z++) {
            if (z < 22 || z >= 27) f.layer(24, 64, z, "SSSSS");
        }
        TileRequest req = request(f, new Seed(3, 65, 24));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node j = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(26, j.x(), d);
        assertEquals(24, j.z(), d);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(4, r.edges().size(), d);
        Map<String, Double> byFarEnd = new HashMap<>();
        for (Edge e : r.edges()) {
            assertEquals(2, e.geometry().size(), "straight arms" + d);
            Node far = r.node(e.fromKey().equals(j.key()) ? e.toKey() : e.fromKey()).orElseThrow();
            byFarEnd.put(far.x() + "," + far.z(), e.length());
        }
        assertEquals(24.0, byFarEnd.get("2,24"), 1.0, "west arm" + d);
        assertEquals(25.0, byFarEnd.get("51,24"), 1.0, "east arm" + d);
        assertEquals(22.0, byFarEnd.get("26,2"), 1.0, "north arm" + d);
        assertEquals(27.0, byFarEnd.get("26,51"), 1.0, "south arm" + d);
    }

    @Test
    void fiveWayJunctionOfPaths() {
        // W, E, N, S arms of 1-wide gravel from (20,20) plus a staircase NE arm from the E arm.
        GridFixture f = new GridFixture();
        for (int x = 4; x <= 36; x++) f.block(x, 64, 20, GridFixture.GRAVEL);
        for (int z = 4; z <= 36; z++) f.block(20, 64, z, GridFixture.GRAVEL);
        for (int k = 0; k < 8; k++) {
            f.block(22 + k, 64, 19 - k, GridFixture.GRAVEL);
            f.block(22 + k, 64, 18 - k, GridFixture.GRAVEL);
        }
        TileRequest req = request(f, new Seed(4, 65, 20));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node j = onlyNode(r, RoadNodeKind.JUNCTION);
        assertTrue(Math.abs(j.x() - 20) <= 3 && Math.abs(j.z() - 20) <= 3, "one junction near (20,20): " + j + d);
        assertEquals(5, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(5, r.edges().size(), d);
    }

    @Test
    void plazaWithFourExitsIsOneJunction() {
        // 15×15 plaza (x,z 20..34) with 3-wide exits of 16 cells on all four sides.
        GridFixture f = new GridFixture();
        for (int z = 20; z <= 34; z++) f.layer(20, 64, z, "S".repeat(15));
        for (int z = 26; z <= 28; z++) {
            f.layer(4, 64, z, "S".repeat(16));
            f.layer(35, 64, z, "S".repeat(16));
        }
        for (int z = 4; z < 20; z++) f.layer(26, 64, z, "SSS");
        for (int z = 35; z < 51; z++) f.layer(26, 64, z, "SSS");
        TileRequest req = request(f, new Seed(27, 65, 27));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node plaza = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(27, plaza.x(), d);
        assertEquals(27, plaza.z(), d);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(4, r.edges().size(), d);
        for (Edge e : r.edges()) {
            assertEquals(23.0, e.length(), 1.5, "centre to exit end" + d);
        }
    }

    @Test
    void anEdgeClimbsToARaisedPlazaAlongItsStairsNotThroughTheGround() {
        GridFixture f = raisedPlazaWithWestStairs();
        TileRequest req = request(f, PARAMS.withPlazaGrowth(6), GridFixture.profiles(), List.of(), PreviousGraph.EMPTY,
            new Seed(3, 65, 27));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node plaza = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(70, plaza.y(), d);
        Edge west = r.edges().stream()
            .filter(e -> r.node(e.fromKey().equals(plaza.key()) ? e.toKey() : e.fromKey()).orElseThrow().x() < 10)
            .findFirst().orElseThrow();
        assertOnTheGround(west, f, d);
        assertTrue(r.warnings().isEmpty(), d);
    }

    @Test
    void geometryThroughTheGroundOrAboveItIsAWarningButStairsAndGatesAreNot() {
        // A 3-wide road at y 64 (x 2..41, z 10..12) with a closed gate at x 30, stairs up to y 70
        // (x 42..47) and a stone hill standing on it at x 10..20.
        GridFixture f = wideRoad(2, 10, 40, 3, 64);
        for (int x = 10; x <= 20; x++) f.column(x, 11, 65, 68, GridFixture.STONE);
        f.gate(7, 30, 64, 11, 3);
        for (int k = 0; k < 6; k++) f.block(42 + k, 65 + k, 11, GridFixture.STAIRS);
        SpanGrid grid = new SpanGrid(f, GridFixture.profiles(), f);

        BuildWarning through = TileBuilder.terrainWarning(grid, List.of(new int[] {4, 64, 11}, new int[] {25, 64, 11}))
            .orElseThrow();
        assertEquals(TileBuilder.WARN_EDGE_UNDERGROUND, through.message());
        assertEquals(List.of(4, 64, 11), List.of(through.x(), through.y(), through.z()));

        BuildWarning above = TileBuilder.terrainWarning(grid,
            List.of(new int[] {22, 64, 11}, new int[] {24, 64, 11}, new int[] {22, 70, 11}, new int[] {40, 70, 11})).orElseThrow();
        assertEquals(TileBuilder.WARN_EDGE_FLOATING, above.message());
        assertEquals(List.of(24, 64, 11), List.of(above.x(), above.y(), above.z()), "the segment that leaves the floor");

        assertTrue(TileBuilder.terrainWarning(grid,
            List.of(new int[] {22, 64, 11}, new int[] {41, 64, 11}, new int[] {47, 70, 11})).isEmpty(), "gate and stairs");
    }

    /**
     * Smoke test 2026-10-04: a 15×15 plaza (x,z 20..34) on a solid hill at y 70; the 3-wide road from
     * the west is flat at y 64 (x 2..13) and climbs six stairs (x 14..19, y 65..70). With a plaza growth
     * of 6 the stairs lie inside the plaza footprint, so the west chain ends at the foot of the stairs.
     */
    private static GridFixture raisedPlazaWithWestStairs() {
        GridFixture f = new GridFixture();
        for (int z = 20; z <= 34; z++) {
            f.layer(20, 70, z, "S".repeat(15));
            for (int x = 20; x <= 34; x++) f.column(x, z, 64, 69, GridFixture.STONE);
        }
        for (int z = 26; z <= 28; z++) {
            f.layer(2, 64, z, "S".repeat(12));
            for (int k = 0; k < 6; k++) {
                f.block(14 + k, 65 + k, z, GridFixture.STAIRS);
                f.column(14 + k, z, 64, 64 + k, GridFixture.STONE);
            }
        }
        return f;
    }

    @Test
    void anAnchorAtTheEdgeOfAPlazaFootprintGetsItsOwnNodeInsteadOfMovingThePlaza() {
        // Smoke test 2026-10-04: anchor 3588 at the foot of Brink's stairs, inside the plaza footprint,
        // took over the plaza junction 21 blocks away, so every plaza arm started at the stair foot.
        GridFixture f = raisedPlazaWithWestStairs();
        TileRequest req = request(f, PARAMS.withPlazaGrowth(6), GridFixture.profiles(), List.of(new Anchor(77, 13, 65, 27)),
            PreviousGraph.EMPTY, new Seed(3, 65, 27));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node plaza = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(List.of(27, 70, 27), List.of(plaza.x(), plaza.y(), plaza.z()), "the plaza stays at its core" + d);
        Node anchor = onlyNode(r, RoadNodeKind.ANCHOR);
        assertEquals(OptionalInt.of(77), anchor.existingId(), d);
        assertEquals(List.of(13, 65, 27), List.of(anchor.x(), anchor.y(), anchor.z()), d);
        assertTrue(r.edges().stream().anyMatch(e -> Set.of(e.fromKey(), e.toKey()).equals(Set.of(plaza.key(), anchor.key()))),
            "the anchor and the plaza are joined" + d);
        Node west = onlyNode(r, RoadNodeKind.ENDPOINT);
        assertTrue(r.edges().stream().anyMatch(e -> Set.of(e.fromKey(), e.toKey()).equals(Set.of(west.key(), anchor.key()))),
            "the west road leaves from the anchor, not from the plaza" + d);
        for (Edge e : r.edges()) {
            assertOnTheGround(e, f, d);
        }
        assertTrue(r.warnings().isEmpty(), d);
    }

    /** No sampled point of the edge has solid blocks at both feet and head height (1-block tolerance for slopes). */
    private static void assertOnTheGround(Edge e, GridFixture f, String d) {
        List<int[]> g = e.geometry();
        for (int s = 0; s + 1 < g.size(); s++) {
            int[] a = g.get(s);
            int[] b = g.get(s + 1);
            for (int i = 0; i <= 20; i++) {
                double t = i / 20.0;
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * t);
                int y = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int z = (int) Math.round(a[2] + (b[2] - a[2]) * t);
                assertFalse(f.isSolid(x, y + 1, z) && f.isSolid(x, y + 2, z),
                    "segment " + s + " runs through the ground at (" + x + ", " + y + ", " + z + ")" + d);
            }
        }
    }

    @Test
    void stairsUpAHill() {
        // 3-wide stone road: flat at y 64 (x 4..13), stairs rising one block per step (x 14..19, y 65..70),
        // flat at y 70 (x 20..29).
        GridFixture f = new GridFixture();
        for (int z = 10; z < 13; z++) {
            f.layer(4, 64, z, "S".repeat(10));
            for (int k = 0; k < 6; k++) f.block(14 + k, 65 + k, z, GridFixture.STAIRS);
            f.layer(20, 70, z, "S".repeat(10));
        }
        TileRequest req = request(f, new Seed(5, 65, 11));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        Edge e = onlyEdge(r);
        // 10 flat + 6 diagonal steps (√2 each... one block up per block forward) + 10 flat = 25 + 6√2 - adjustments
        assertEquals(19 + 6 * Math.sqrt(2), e.length(), 1.0, d);
        assertEquals(64, e.geometry().get(0)[1], d);
        assertEquals(70, e.geometry().get(e.geometry().size() - 1)[1], d);
        assertTrue(e.geometry().size() >= 4, "RDP keeps the foot and the top of the stairs" + d);
        assertEquals(1, r.levelCount());
    }

    @Test
    void tunnelUnderARoadStaysSeparate() {
        GridFixture f = wideRoad(4, 20, 40, 3, 70);           // road at y 70, x 4..43, z 20..22
        for (int z = 4; z < 44; z++) f.layer(20, 64, z, "SSS"); // tunnel at y 64 crossing under it
        for (int z = 4; z < 44; z++) f.layer(20, 67, z, "XXX"); // tunnel ceiling
        TileRequest req = request(f, new Seed(5, 71, 21), new Seed(21, 65, 5));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), "no junction between the levels" + d);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(2, r.edges().size(), d);
        assertEquals(2, r.levelCount());
        for (Edge e : r.edges()) {
            assertEquals(39.0, e.length(), 1.0, d);
            assertEquals(e.geometry().get(0)[1], e.geometry().get(1)[1], "each edge stays on its level");
        }
    }

    @Test
    void bridgeOverARoadStaysSeparate() {
        GridFixture f = wideRoad(4, 20, 40, 3, 64);           // road at y 64
        for (int z = 4; z < 44; z++) f.layer(20, 68, z, "SSS"); // bridge deck at y 68: 65-67 free below it
        TileRequest req = request(f, new Seed(5, 65, 21), new Seed(21, 69, 5));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), d);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(2, r.edges().size(), d);
        assertTrue(r.edges().stream().anyMatch(e -> e.geometry().get(0)[1] == 64), d);
        assertTrue(r.edges().stream().anyMatch(e -> e.geometry().get(0)[1] == 68), d);
    }

    @Test
    void twoStackedStreets() {
        GridFixture f = wideRoad(4, 10, 40, 5, 64);
        for (int z = 10; z < 15; z++) f.layer(4, 71, z, "S".repeat(40));
        TileRequest req = request(f, new Seed(10, 65, 12), new Seed(10, 72, 12));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(2, r.edges().size(), d);
        assertEquals(2, r.levelCount());
        for (Edge e : r.edges()) {
            assertEquals(39.0, e.length(), 1.0, d);
        }
        assertEquals(400, r.cellCount());
    }

    @Test
    void spiralRamp() {
        // A 2-wide ramp around a 6×6 core (x,z 20..25), one block up every 2 cells, going round twice.
        GridFixture f = new GridFixture();
        int[][] path = spiralCells();
        int y = 64;
        for (int i = 0; i < path.length; i++) {
            if (i > 0 && i % 2 == 0) y++;
            f.block(path[i][0], y, path[i][1], GridFixture.STONE_BRICKS);
            f.block(path[i][0] + path[i][2], y, path[i][1] + path[i][3], GridFixture.STONE_BRICKS); // second lane
        }
        TileRequest req = request(f, new Seed(path[0][0], 65, path[0][1]));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), d);
        Edge e = onlyEdge(r);
        int top = Math.max(e.geometry().get(0)[1], e.geometry().get(e.geometry().size() - 1)[1]);
        int bottom = Math.min(e.geometry().get(0)[1], e.geometry().get(e.geometry().size() - 1)[1]);
        assertEquals(64, bottom, d);
        assertTrue(top >= 64 + (path.length - 1) / 2 - 1, "climbs the whole ramp" + d);
        assertTrue(e.length() >= path.length - 4 && e.length() <= path.length + 6, "walks the ramp: " + e.length() + d);
        assertTrue(r.levelCount() >= 2, "the ramp stacks over itself" + d);
    }

    /** Outer-lane cells of a square spiral around x,z 20..25 with the inward offset of the second lane. */
    private static int[][] spiralCells() {
        List<int[]> cells = new ArrayList<>();
        // Ring around the core: x 18..27, z 18..27 (outer lane), second lane one cell inwards.
        int x = 18, z = 27;
        for (; z > 18; z--) cells.add(new int[] {x, z, 1, 0});        // west side going north
        for (; x < 27; x++) cells.add(new int[] {x, z, 0, 1});        // north side going east
        for (; z < 27; z++) cells.add(new int[] {x, z, -1, 0});       // east side going south
        for (; x > 18; x--) cells.add(new int[] {x, z, 0, -1});       // south side going west
        // Second lap (the ramp passes over its start).
        for (; z > 18; z--) cells.add(new int[] {x, z, 1, 0});
        for (; x < 27; x++) cells.add(new int[] {x, z, 0, 1});
        return cells.toArray(new int[0][]);
    }

    @Test
    void mixedProfilesGiveOneContinuousEdgeWithTheDominantProfile() {
        GridFixture f = new GridFixture().layer(4, 64, 10, "G".repeat(10) + "S".repeat(20));
        TileRequest req = request(f, new Seed(4, 65, 10));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        Edge e = onlyEdge(r);
        assertEquals(29.0, e.length(), 1e-9, d);
        assertEquals(OptionalInt.of(1), e.profileId(), "20 stone bricks beat 10 gravel" + d);

        GridFixture g = new GridFixture().layer(4, 64, 10, "G".repeat(20) + "S".repeat(10));
        assertEquals(OptionalInt.of(2), onlyEdge(builder.build(request(g, new Seed(4, 65, 10)), g)).profileId());
    }

    @Test
    void cobblestoneCourtyardNextToACobblestoneKerbIsHeldByAmbiguousReach() {
        // 3-wide stone-brick road (z 10..12), cobblestone kerb (z 13), cobblestone courtyard (z 14..25).
        GridFixture f = new GridFixture();
        for (int z = 10; z < 13; z++) f.layer(4, 64, z, "S".repeat(30));
        for (int z = 13; z < 26; z++) f.layer(4, 64, z, "c".repeat(30));
        TileRequest req = request(f, new Seed(5, 65, 11));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        Edge e = onlyEdge(r);
        assertEquals(29.0, e.length(), 1.0, d);
        // 3 road rows + kerb + 2 more courtyard rows within reach 3 = 6 rows; the rest of the courtyard is out.
        assertEquals(6 * 30, r.cellCount(), d);
        int centreZ = e.geometry().get(0)[2];
        assertTrue(centreZ >= 11 && centreZ <= 13, "centreline within the road/kerb band: " + centreZ + d);
        assertEquals(OptionalInt.of(1), e.profileId(), d);
    }

    @Test
    void gapOfTwoAirBlocksGivesTwoComponentsAndNoEdgeAcross() {
        GridFixture f = new GridFixture().layer(4, 64, 10, "S".repeat(14) + ".." + "S".repeat(14));
        TileRequest req = request(f, new Seed(4, 65, 10), new Seed(30, 65, 10));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(4, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(2, r.edges().size(), d);
        for (Edge e : r.edges()) {
            assertEquals(13.0, e.length(), 1e-9, d);
        }
        // Positions 17 and 18 are the gap: no node or geometry point there.
        for (Node n : r.nodes()) {
            assertTrue(n.x() != 18 && n.x() != 19, d);
        }
    }

    @Test
    void closedGateOnTheRoadGivesAnEdgeWithGateDoorIds() {
        GridFixture f = wideRoad(4, 10, 30, 3, 64).gate(42, 18, 64, 10, 2).gate(42, 18, 64, 11, 2).gate(42, 18, 64, 12, 2);
        TileRequest req = request(f, new Seed(5, 65, 11));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), "the closed gate does not split the road" + d);
        Edge e = onlyEdge(r);
        assertEquals(List.of(42), e.gateDoorIds(), d);
        assertEquals(29.0, e.length(), 1e-9, d);

        // Without the gate cells the closed door blocks the road: two pieces, no gate ids.
        SpanGrid ignored = new SpanGrid(f, GridFixture.profiles(), GateCells.NONE);
        TileRequest without = new TileRequest("world", 0, 0, PARAMS, List.of(new Seed(5, 65, 11), new Seed(30, 65, 11)),
            GridFixture.profiles(), GateCells.NONE, List.of(), PreviousGraph.EMPTY);
        TileBuildResult r2 = builder.build(without, f);
        assertEquals(2, r2.edges().size(), describe(r2));
        assertTrue(r2.edges().stream().allMatch(x -> x.gateDoorIds().isEmpty()));
        assertTrue(ignored.isSpan(5, 64, 11));
    }

    @Test
    void tileBorderCrossingGivesBoundaryNodes() {
        // Road from x -6 to x 70 through tile 0 (x 0..63, margin 8).
        GridFixture f = new GridFixture();
        for (int z = 20; z < 23; z++) f.layer(-6, 64, z, "S".repeat(77));
        TileRequest req = request(f, new Seed(30, 65, 21));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(2, r.nodes(RoadNodeKind.BOUNDARY).size(), d);
        assertEquals(2, r.nodes().size(), d);
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 0 && n.z() == 21), d);
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 63 && n.z() == 21), d);
        Edge e = onlyEdge(r);
        assertEquals(63.0, e.length(), 1e-9, d);
        assertEquals(3 * 77, r.cellCount(), "the whole road (x -6..70) lies within tile + margin (x -8..71)");
    }

    @Test
    void roadEndingInsideTheTileAndLeavingOnTheOtherSide() {
        GridFixture f = new GridFixture().layer(10, 64, 30, "G".repeat(70));
        TileRequest req = request(f, new Seed(10, 65, 30));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(1, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(1, r.nodes(RoadNodeKind.BOUNDARY).size(), d);
        assertEquals(53.0, onlyEdge(r).length(), 1e-9, d);
    }

    @Test
    void neighbourTileSeesTheSameRoadFromItsSide() {
        GridFixture f = new GridFixture();
        for (int z = 20; z < 23; z++) f.layer(-6, 64, z, "S".repeat(77));
        TileRequest req = new TileRequest("world", 1, 0, PARAMS, List.of(new Seed(66, 65, 21)), GridFixture.profiles(), f,
            List.of(), PreviousGraph.EMPTY);
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(1, r.nodes(RoadNodeKind.BOUNDARY).size(), d);
        assertTrue(r.nodes().stream().anyMatch(n -> n.x() == 64 && n.z() == 21), "on tile 1's west border" + d);
        assertEquals(1, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(6.0, onlyEdge(r).length(), 1e-9, d);
    }

    @Test
    void rebuildWithOneBlockChangedKeepsAllOtherExistingIds() {
        GridFixture f = wideRoad(4, 4, 40, 5, 64);
        for (int z = 9; z < 40; z++) f.layer(21, 64, z, "SSSSS");
        for (int z = 20; z < 23; z++) f.layer(26, 64, z, "S".repeat(20)); // side street off the stem
        TileRequest first = request(f, new Seed(5, 65, 6));
        TileBuildResult before = builder.build(first, f);
        assertContract(before, first);
        assertEquals(2, before.nodes(RoadNodeKind.JUNCTION).size(), describe(before));

        // Pretend the API stored it: ids 100+ for nodes, 200+ for edges.
        List<PreviousNode> nodes = new ArrayList<>();
        Map<String, Integer> idByKey = new HashMap<>();
        for (int i = 0; i < before.nodes().size(); i++) {
            Node n = before.nodes().get(i);
            idByKey.put(n.key(), 100 + i);
            nodes.add(new PreviousNode(100 + i, n.x(), n.y(), n.z(), n.kind(), false));
        }
        List<PreviousEdge> edges = new ArrayList<>();
        for (int i = 0; i < before.edges().size(); i++) {
            Edge e = before.edges().get(i);
            edges.add(new PreviousEdge(200 + i, idByKey.get(e.fromKey()), idByKey.get(e.toKey()), e.geometry()));
        }
        PreviousGraph previous = new PreviousGraph(nodes, edges);

        // One block changes far from every node: a kerb block becomes grass at the road's edge.
        f.block(12, 64, 4, GridFixture.GRASS);
        TileRequest second = request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(5, 65, 6));
        TileBuildResult after = builder.build(second, f);
        String d = describe(before) + describe(after);

        assertContract(after, second);
        assertEquals(before.nodes().size(), after.nodes().size(), d);
        assertEquals(before.edges().size(), after.edges().size(), d);
        for (Node n : after.nodes()) {
            assertTrue(n.existingId().isPresent(), "every node matched: " + n + d);
        }
        assertEquals(before.nodes().size(), after.nodes().stream().map(n -> n.existingId().getAsInt()).distinct().count(), d);
        for (Edge e : after.edges()) {
            assertTrue(e.existingId().isPresent(), "every edge matched: " + e.fromKey() + "-" + e.toKey() + d);
        }
        assertEquals(before.cellCount() - 1, after.cellCount());
    }

    @Test
    void lockedPreviousNodeKeepsItsPosition() {
        GridFixture f = new GridFixture().layer(4, 64, 10, "G".repeat(30));
        PreviousGraph previous = new PreviousGraph(List.of(new PreviousNode(7, 5, 64, 11, RoadNodeKind.ENDPOINT, true)), List.of());
        TileBuildResult r = builder.build(request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(4, 65, 10)), f);

        Node locked = r.nodes().stream().filter(n -> n.existingId().equals(OptionalInt.of(7))).findFirst().orElseThrow();
        assertEquals(5, locked.x());
        assertEquals(11, locked.z(), "the locked node did not move");
        Edge e = onlyEdge(r);
        int[] end = e.fromKey().equals(locked.key()) ? e.geometry().get(0) : e.geometry().get(e.geometry().size() - 1);
        assertEquals(11, end[2], "geometry follows the node");
    }

    /**
     * Fix plan 5.5 item 6: the builder makes two junctions 5 blocks apart (a side path south at x 15,
     * one north at x 20 - farther apart than junction-cluster-radius). The admin merged them into
     * the west one (#100, locked; #101 is gone). Two rebuilds - the second after a block changed -
     * must keep one junction, #100, with all four arms.
     */
    private static GridFixture twoCloseForks(boolean brokenBlock) {
        GridFixture f = new GridFixture().layer(4, 64, 20, "G".repeat(40));   // main path z 20, x 4..43
        for (int z = 21; z <= 40; z++) f.block(15, 64, z, GridFixture.GRAVEL); // south arm at x 15
        for (int z = 2; z <= 19; z++) f.block(20, 64, z, GridFixture.GRAVEL);  // north arm at x 20
        if (brokenBlock) {
            f.clear(43, 64, 20); // the main path loses its last block in the east
        }
        return f;
    }

    @Test
    void aMergedJunctionStaysMergedAcrossRebuilds() {
        TileBuildResult fresh = builder.build(request(twoCloseForks(false), new Seed(4, 65, 20)), twoCloseForks(false));
        assertEquals(2, fresh.nodes(RoadNodeKind.JUNCTION).size(), "the builder alone makes two" + describe(fresh));

        PreviousGraph merged = new PreviousGraph(List.of(new PreviousNode(100, 15, 64, 20, RoadNodeKind.JUNCTION, true)), List.of());
        for (boolean broken : new boolean[] {false, true}) {
            GridFixture f = twoCloseForks(broken);
            TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(), merged, new Seed(4, 65, 20));
            TileBuildResult r = builder.build(req, f);
            String d = describe(r);

            assertContract(r, req);
            Node junction = onlyNode(r, RoadNodeKind.JUNCTION);
            assertEquals(OptionalInt.of(100), junction.existingId(), d);
            assertEquals(15, junction.x(), d);
            assertEquals(20, junction.z(), d);
            assertEquals(4, r.edges().size(), "west, east, south and north arms" + d);
            assertTrue(r.edges().stream().allMatch(e -> e.fromKey().equals(junction.key()) || e.toKey().equals(junction.key())), d);
        }
    }

    @Test
    void aLockedJunctionClaimsTheBuildersJunctionBeyondTheNormalMatchDistance() {
        // The admin locked the junction where they wanted it, 5 blocks along the south arm (a plaza's
        // re-centred junction looks the same): the rebuild's junction takes its id and position
        // instead of a new junction appearing next to it.
        GridFixture f = new GridFixture().layer(4, 64, 20, "G".repeat(40));
        for (int z = 21; z <= 40; z++) f.block(15, 64, z, GridFixture.GRAVEL);
        PreviousGraph previous = new PreviousGraph(List.of(new PreviousNode(200, 15, 64, 25, RoadNodeKind.JUNCTION, true)), List.of());
        TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(4, 65, 20));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node junction = onlyNode(r, RoadNodeKind.JUNCTION);
        assertEquals(OptionalInt.of(200), junction.existingId(), d);
        assertEquals(25, junction.z(), "the locked position" + d);

        TileBuildResult unlocked = builder.build(request(f, PARAMS, GridFixture.profiles(), List.of(),
            new PreviousGraph(List.of(new PreviousNode(200, 15, 64, 25, RoadNodeKind.JUNCTION, false)), List.of()),
            new Seed(4, 65, 20)), f);
        assertTrue(onlyNode(unlocked, RoadNodeKind.JUNCTION).existingId().isEmpty(), "unlocked: only within 3 blocks" + describe(unlocked));
    }

    @Test
    void aBorderNodeNeverMovesOntoALockedJunctionInsideTheTile() {
        // Smoke test 2026-10-02, tile 2,-2: locked junction "Brink" 7 blocks inside the border; the road
        // leaves the tile next to it. The Boundary node must stay on the border (the API refuses it
        // anywhere else), not take Brink's id and position.
        GridFixture f = new GridFixture().layer(40, 64, 10, "G".repeat(36)); // x 40..75, tile 0..63
        PreviousGraph previous = new PreviousGraph(List.of(new PreviousNode(7, 56, 64, 10, RoadNodeKind.JUNCTION, true)), List.of());
        TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(40, 65, 10));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node border = onlyNode(r, RoadNodeKind.BOUNDARY);
        assertEquals(63, border.x(), d);
        assertTrue(border.existingId().isEmpty(), d);
    }

    /** A path along z = 20 (x 4..43) with a 20-block dead end south at x = 15 (end at z = 40). */
    private static GridFixture pathWithSpur() {
        GridFixture f = new GridFixture().layer(4, 64, 20, "G".repeat(40));
        for (int z = 21; z <= 40; z++) f.block(15, 64, z, GridFixture.GRAVEL);
        return f;
    }

    @Test
    void aPrunedDeadEndIsLeftOutAndItsJunctionDissolves() {
        GridFixture f = pathWithSpur();
        TileBuildResult plain = builder.build(request(f, new Seed(4, 65, 20)), f);
        assertEquals(1, plain.nodes(RoadNodeKind.JUNCTION).size(), describe(plain));
        assertEquals(3, plain.edges().size(), describe(plain));

        // The admin pruned the dead end's endpoint; the tombstone is a few blocks off the rebuilt end.
        PreviousGraph previous = new PreviousGraph(List.of(new PreviousNode(300, 15, 64, 37, RoadNodeKind.PRUNED, true)), List.of());
        TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(4, 65, 20));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), "the junction is left with two arms and dissolves" + d);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(1, r.edges().size(), "one edge along the path" + d);
        assertTrue(r.nodes().stream().noneMatch(n -> n.existingId().equals(OptionalInt.of(300))), "the tombstone is never emitted" + d);
    }

    @Test
    void aTombstoneFartherThanTheReachChangesNothing() {
        GridFixture f = pathWithSpur();
        PreviousGraph previous = new PreviousGraph(List.of(new PreviousNode(300, 15, 64, 52, RoadNodeKind.PRUNED, true)), List.of());
        TileBuildResult r = builder.build(request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(4, 65, 20)), f);
        assertEquals(1, r.nodes(RoadNodeKind.JUNCTION).size(), "12 blocks from the dead end, reach 8" + describe(r));
        assertEquals(3, r.edges().size(), describe(r));
    }

    // ---- pruned edges (smoke test 2026-10-03: a deleted detected edge came back on every rebuild) ----

    private TileBuildResult buildWithEdgeTombstones(GridFixture f, int[]... tombstones) {
        List<PreviousNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < tombstones.length; i++) {
            int[] t = tombstones[i];
            nodes.add(new PreviousNode(400 + i, t[0], t[1], t[2], RoadNodeKind.PRUNED_EDGE, true));
        }
        TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(), new PreviousGraph(nodes, List.of()), new Seed(4, 65, 20));
        TileBuildResult r = builder.build(req, f);
        assertContract(r, req);
        assertTrue(r.nodes().stream().noneMatch(n -> n.existingId().isPresent() && n.existingId().getAsInt() >= 400),
            "a tombstone is never emitted" + describe(r));
        return r;
    }

    private static int minX(TileBuildResult.Edge edge) {
        return edge.geometry().stream().mapToInt(p -> p[0]).min().orElseThrow();
    }

    @Test
    void aPrunedEdgeIsLeftOutAndItsJunctionDissolves() {
        TileBuildResult r = buildWithEdgeTombstones(pathWithSpur(), new int[] {15, 64, 30}); // the spur's middle
        String d = describe(r);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), "the junction is left with two arms and dissolves" + d);
        assertEquals(2, r.nodes(RoadNodeKind.ENDPOINT).size(), d);
        assertEquals(1, r.edges().size(), "one edge along the path" + d);
    }

    @Test
    void aPrunedEdgeOnTheThroughRoadCutsIt() {
        TileBuildResult r = buildWithEdgeTombstones(pathWithSpur(), new int[] {9, 64, 20}); // the west arm's middle
        String d = describe(r);
        assertEquals(0, r.nodes(RoadNodeKind.JUNCTION).size(), d);
        assertEquals(1, r.edges().size(), "the spur and the east arm join into one edge" + d);
        assertTrue(minX(r.edges().get(0)) >= 14, "nothing west of the junction is left" + d);
    }

    @Test
    void prunedEdgesArePickedBeforeAnyIsRemoved() {
        // Removing the west arm first would dissolve the junction and join the spur to the east arm;
        // the spur's tombstone must not then take the east arm with it.
        TileBuildResult r = buildWithEdgeTombstones(pathWithSpur(), new int[] {9, 64, 20}, new int[] {15, 64, 30});
        String d = describe(r);
        assertEquals(1, r.edges().size(), "the east arm stays" + d);
        assertTrue(minX(r.edges().get(0)) >= 14, d);
        assertTrue(r.edges().get(0).geometry().stream().allMatch(p -> p[2] <= 21), "no spur in it (the old junction point is z = 21)" + d);
    }

    @Test
    void everyEdgeOfAJunctionPrunedLeavesNothing() {
        TileBuildResult r = buildWithEdgeTombstones(pathWithSpur(), new int[] {9, 64, 20}, new int[] {30, 64, 20}, new int[] {15, 64, 30});
        assertEquals(0, r.nodes().size(), describe(r));
        assertEquals(0, r.edges().size(), describe(r));
    }

    @Test
    void anEdgeTombstoneFartherThanTheMatchDistanceChangesNothing() {
        TileBuildResult r = buildWithEdgeTombstones(pathWithSpur(), new int[] {25, 64, 25}); // 5 blocks off the path
        assertEquals(1, r.nodes(RoadNodeKind.JUNCTION).size(), describe(r));
        assertEquals(3, r.edges().size(), describe(r));
    }

    // ---- two-arm junctions (smoke test 2026-10-02, junction #3615) ----

    private static SkeletonGraph.Result threeNodes(RoadNodeKind middle) {
        return new SkeletonGraph.Result(List.of(
            new SkeletonGraph.Node(0, 0, 64, 0, RoadNodeKind.ENDPOINT, OptionalInt.empty(), 0),
            new SkeletonGraph.Node(1, 10, 64, 0, middle, OptionalInt.empty(), 10),
            new SkeletonGraph.Node(2, 20, 64, 0, RoadNodeKind.BOUNDARY, OptionalInt.empty(), 20)), List.of(), List.of());
    }

    private static TileBuilder.Run run(int from, int to, int x0, int x1, int span0) {
        List<int[]> polyline = new ArrayList<>();
        int step = x1 >= x0 ? 1 : -1;
        int[] spans = new int[Math.abs(x1 - x0) + 1];
        for (int x = x0, k = 0; ; x += step, k++) {
            polyline.add(new int[] {x, 64, 0});
            spans[k] = span0 + k * step;
            if (x == x1) break;
        }
        return new TileBuilder.Run(from, to, polyline, spans);
    }

    private static Map<Long, TileBuilder.Run> runs(TileBuilder.Run... runs) {
        Map<Long, TileBuilder.Run> map = new java.util.LinkedHashMap<>();
        for (TileBuilder.Run r : runs) map.put(TileBuilder.pairKey(r.from(), r.to()), r);
        return map;
    }

    @Test
    void aJunctionLeftWithTwoArmsIsJoinedIntoOneEdge() {
        // A(0) -> J(10) and B(20) -> J(10): the second run points the other way, like a real build can.
        Map<Long, TileBuilder.Run> runs = runs(run(0, 1, 0, 10, 0), run(2, 1, 20, 10, 20));
        Set<Integer> gone = TileBuilder.dissolveTwoArmJunctions(runs, threeNodes(RoadNodeKind.JUNCTION),
            new int[] {-1, 3615, -1}, new boolean[3], PreviousGraph.EMPTY);

        assertEquals(Set.of(1), gone);
        assertEquals(1, runs.size());
        TileBuilder.Run joined = runs.values().iterator().next();
        assertEquals(java.util.Set.of(0, 2), java.util.Set.of(joined.from(), joined.to()));
        assertEquals(21, joined.polyline().size(), "x 0..20, the joint once");
        assertEquals(20, joined.length(), 1e-9);
        for (int k = 1; k < joined.polyline().size(); k++) {
            assertEquals(1, Math.abs(joined.polyline().get(k)[0] - joined.polyline().get(k - 1)[0]), "continuous");
        }
        assertEquals(22, joined.spans().length);
    }

    @Test
    void aTwoArmJunctionStaysWhenLockedOrOnASplitLoopOrWithAThirdPreviousEdge() {
        Map<Long, TileBuilder.Run> runs = runs(run(0, 1, 0, 10, 0), run(1, 2, 10, 20, 10));
        assertTrue(TileBuilder.dissolveTwoArmJunctions(runs, threeNodes(RoadNodeKind.JUNCTION), new int[] {-1, 7, -1},
            new boolean[] {false, true, false}, PreviousGraph.EMPTY).isEmpty(), "locked by an admin");
        assertTrue(TileBuilder.dissolveTwoArmJunctions(runs, threeNodes(RoadNodeKind.ANCHOR), new int[] {-1, -1, -1},
            new boolean[3], PreviousGraph.EMPTY).isEmpty(), "only Junctions");

        PreviousGraph withRecording = new PreviousGraph(List.of(), List.of(
            new PreviousEdge(1, 7, 8, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0})),
            new PreviousEdge(2, 7, 9, List.of(new int[] {10, 64, 0}, new int[] {20, 64, 0})),
            new PreviousEdge(3, 7, 10, List.of(new int[] {10, 64, 0}, new int[] {10, 64, 30}))));
        assertTrue(TileBuilder.dissolveTwoArmJunctions(runs, threeNodes(RoadNodeKind.JUNCTION), new int[] {-1, 7, -1},
            new boolean[3], withRecording).isEmpty(), "three edges last time: a recorded edge may end there");

        // A loop split into three: joining any split point would make a second run between the others.
        Map<Long, TileBuilder.Run> loop = runs(run(0, 1, 0, 10, 0), run(1, 2, 10, 20, 10), run(2, 0, 20, 0, 20));
        SkeletonGraph.Result triangle = new SkeletonGraph.Result(List.of(
            new SkeletonGraph.Node(0, 0, 64, 0, RoadNodeKind.JUNCTION, OptionalInt.empty(), 0),
            new SkeletonGraph.Node(1, 10, 64, 0, RoadNodeKind.JUNCTION, OptionalInt.empty(), 10),
            new SkeletonGraph.Node(2, 20, 64, 0, RoadNodeKind.JUNCTION, OptionalInt.empty(), 20)), List.of(), List.of());
        assertTrue(TileBuilder.dissolveTwoArmJunctions(loop, triangle, new int[] {-1, -1, -1}, new boolean[3], PreviousGraph.EMPTY).isEmpty());
        assertEquals(3, loop.size());
    }

    @Test
    void anUnlockedNeighbourAndAFarJunctionAreNotMerged() {
        // Only unmatched nodes merge: a junction the admin left alone (#101, matched) stays, and so does
        // one farther than locked-node-reach.
        GridFixture f = twoCloseForks(false);
        PreviousGraph previous = new PreviousGraph(List.of(
            new PreviousNode(100, 15, 64, 20, RoadNodeKind.JUNCTION, true),
            new PreviousNode(101, 20, 64, 20, RoadNodeKind.JUNCTION, false)), List.of());
        TileBuildResult r = builder.build(request(f, PARAMS, GridFixture.profiles(), List.of(), previous, new Seed(4, 65, 20)), f);
        assertEquals(2, r.nodes(RoadNodeKind.JUNCTION).size(), describe(r));

        TileBuildResult shortReach = builder.build(request(f, PARAMS.withLockedNodeReach(4), GridFixture.profiles(), List.of(),
            new PreviousGraph(List.of(new PreviousNode(100, 15, 64, 20, RoadNodeKind.JUNCTION, true)), List.of()),
            new Seed(4, 65, 20)), f);
        assertEquals(2, shortReach.nodes(RoadNodeKind.JUNCTION).size(), "5 blocks apart, reach 4" + describe(shortReach));
    }

    @Test
    void anchorsAppearAsAnchorNodesWithTheirIds() {
        GridFixture f = new GridFixture().layer(4, 64, 10, "G".repeat(30));
        TileRequest req = request(f, PARAMS, GridFixture.profiles(), List.of(new Anchor(55, 18, 65, 10)), PreviousGraph.EMPTY,
            new Seed(4, 65, 10));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertContract(r, req);
        Node anchor = onlyNode(r, RoadNodeKind.ANCHOR);
        assertEquals(OptionalInt.of(55), anchor.existingId(), d);
        assertEquals(65, anchor.y(), "the admin's position is kept" + d);
        assertEquals(2, r.edges().size(), d);
        assertTrue(r.edges().stream().allMatch(e -> e.fromKey().equals(anchor.key()) || e.toKey().equals(anchor.key())), d);
    }

    @Test
    void warningsAreCollectedFromEveryStage() {
        GridFixture f = new GridFixture().layer(4, 64, 10, "G".repeat(30));
        TileRequest req = request(f, PARAMS.withMaxCells(10), GridFixture.profiles(), List.of(new Anchor(1, 50, 64, 50)),
            PreviousGraph.EMPTY, new Seed(4, 65, 10), new Seed(60, 65, 60));
        TileBuildResult r = builder.build(req, f);

        List<String> texts = r.warningTexts();
        assertEquals(3, texts.size(), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(MaskBuilder.WARN_SEED_UNMATCHED)), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(MaskBuilder.WARN_CELL_CAP)), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith(SkeletonGraph.WARN_ANCHOR_OFF_ROAD)), texts.toString());
        assertEquals(10, r.cellCount());
    }

    @Test
    void emptyWorldBuildsAnEmptyTile() {
        GridFixture f = new GridFixture();
        TileRequest req = request(f, new Seed(4, 65, 10));
        TileBuildResult r = builder.build(req, f);
        assertEquals(0, r.nodes().size());
        assertEquals(0, r.edges().size());
        assertEquals(0, r.cellCount());
        assertEquals(0, r.levelCount());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void scopedProfileOnlyBuildsInsideItsTown() {
        Profile scoped = new Profile(9, "Kardenna", true, 1, 5, Set.of(42), List.of(
            GridFixture.mat(GridFixture.DIRT_PATH, RoadMaterialRole.SURFACE, false, 1.0)));
        GridFixture f = new GridFixture().layer(4, 64, 10, "D".repeat(0));
        for (int x = 4; x < 34; x++) f.block(x, 64, 10, GridFixture.DIRT_PATH);
        ScopeLookup town = (x, z) -> x < 20 ? OptionalInt.of(42) : OptionalInt.empty();
        ProfileSet profiles = new ProfileSet(List.of(GridFixture.townRoad(), scoped), town);
        TileRequest req = request(f, PARAMS, profiles, List.of(), PreviousGraph.EMPTY, new Seed(4, 65, 10));
        TileBuildResult r = builder.build(req, f);
        String d = describe(r);

        assertEquals(16, r.cellCount(), "x 4..19 only" + d);
        assertEquals(15.0, onlyEdge(r).length(), 1e-9, d);
        assertEquals(OptionalInt.of(9), onlyEdge(r).profileId(), d);
    }
}
