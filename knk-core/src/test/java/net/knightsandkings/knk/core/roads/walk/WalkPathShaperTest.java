package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.GridWorld;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shaping walk paths off the main thread (off-road navigation phase 6.1, PandiO/knk-workspace#710, DESIGN § 7.1,
 * § 13 A15 and A27): {@link WalkPathShaper} over {@link WalkTerrainNavWorld}.
 */
class WalkPathShaperTest {

    private static final String G = GridFixture.GRASS;
    private static final MovementProfile CLEARANCE = MovementProfile.PLAYER.withClearance(5, 0.8);
    private static final WalkPathShaper.Options SHAPE = WalkPathShaper.Options.of(0.8, true);
    /**
     * How far under {@code wall-clearance} the shared shaper's line may pass: its string pulling keeps the points'
     * clearance less 0.05 and checks a step every 0.25 blocks, so at a convex corner the line can dip a few hundredths
     * more between two checks (NPC code, unchanged, A26).
     */
    private static final double SHAPER_TOLERANCE = 0.1;

    // ===== A15: the spiral stair =====

    @Test
    void onASpiralStairOfWidthThreeTheTrailKeepsTheClearanceFromTheNewel() {
        WalkFixture f = SpiralStair.fixture();
        WalkRequest up = f.request(5, SpiralStair.BASE, 3, 5, SpiralStair.BASE + 2 * SpiralStair.RISE, 3)
            .withProfile(CLEARANCE);
        WalkPath path = found(new WalkSearch().find(up));

        double before = least(samples(path.points()), SpiralStair::fromNewel);
        assertTrue(before <= 0.5 + 1e-9, "the cell path clings to the newel: " + before + " " + path);

        WalkPathShaper.Shaped shaped = new WalkPathShaper().shape(path, f.terrain(), CLEARANCE, CellAccess.OPEN, SHAPE);
        List<double[]> trail = samples(shaped.trail());
        double fromNewel = least(trail, SpiralStair::fromNewel);
        double fromWall = least(trail, SpiralStair::fromTowerWall);

        assertTrue(fromNewel >= 0.8 - SHAPER_TOLERANCE,
            "wall-clearance from the newel wherever the stair allows it (everywhere here): " + fromNewel + " " + show(shaped.trail()));
        assertTrue(fromWall >= CorridorShaper.HARD_MINIMUM, "never under the hard minimum from the tower wall: " + fromWall);
        assertTrue(fromNewel >= CorridorShaper.HARD_MINIMUM, "nor from the newel");
        assertEquals(first(path), shaped.trail().get(0)[1], 1e-9, "starts on the start's floor");
        assertArrayEquals(path.points().get(path.size() - 1), shaped.trail().get(shaped.trail().size() - 1), 1e-9,
            "the target stays");
        assertTrue(shaped.rounded() > 0, "corners drawn as curves: " + shaped.rounded() + " of " + shaped.corners());
    }

    @Test
    void theCurveIsCheckedAgainstTheClearanceMapEverywhere() {
        WalkFixture f = SpiralStair.fixture();
        WalkPath path = found(new WalkSearch().find(
            f.request(5, SpiralStair.BASE, 3, 5, SpiralStair.BASE + 2 * SpiralStair.RISE, 3).withProfile(CLEARANCE)));
        WalkPathShaper.Shaped curved = new WalkPathShaper().shape(path, f.terrain(), CLEARANCE, CellAccess.OPEN, SHAPE);
        WalkPathShaper.Shaped straight = new WalkPathShaper().shape(path, f.terrain(), CLEARANCE, CellAccess.OPEN,
            WalkPathShaper.Options.of(0.8, false));

        double line = least(samples(straight.trail()), SpiralStair::fromNewel);
        assertTrue(line >= 0.8 - SHAPER_TOLERANCE, "the shaped line alone keeps it: " + line + " "
            + show(straight.line()));
        assertEquals(least(samples(straight.line()), SpiralStair::fromNewel),
            least(samples(curved.line()), SpiralStair::fromNewel), 1e-9, "the curve does not change the line");
        assertTrue(least(samples(curved.trail()), SpiralStair::fromNewel)
            >= Math.min(0.8, least(samples(curved.line()), SpiralStair::fromNewel)) - 1e-3, // the test samples every 0.1
            "no curve sample nearer the newel than the line allows: " + least(samples(curved.trail()), SpiralStair::fromNewel)
                + " vs " + least(samples(curved.line()), SpiralStair::fromNewel) + " " + show(curved.trail()));
        assertSame(straight.line().size(), straight.trail().size(), "without the curve the trail is the line");
    }

    // ===== A27: the NPC shaper and the player shaper give the same line =====

    @Test
    void oneSceneThroughTheNpcShaperAndThePlayerShaperGivesTheSameLine() {
        // a wall of a building in a field, the line hugging it then turning round its corner (as a cell path does)
        GridWorld npcWorld = new GridWorld().walls(0, 0, 5, 5);
        WalkFixture f = new WalkFixture().floor(-12, -12, 20, 20, 64, G);
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 5; z++) {
                f.column(x, z, 65, 68, GridFixture.STONE);
            }
        }
        int[][] cells = {{-3, 6}, {-2, 6}, {-1, 6}, {0, 6}, {1, 6}, {2, 6}, {3, 6}, {4, 6}, {5, 6}, {6, 6}, {6, 5},
            {6, 4}, {6, 3}, {6, 2}, {6, 1}, {6, 0}, {6, -1}, {6, -2}};
        List<NavPoint> feet = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        long[] keys = new long[cells.length];
        for (int i = 0; i < cells.length; i++) {
            feet.add(new NavPoint(GridWorld.W, cells[i][0] + 0.5, 65, cells[i][1] + 0.5));
            indexes.add(i);
            keys[i] = BlockKey.pack(cells[i][0], 64, cells[i][1]);
        }
        CorridorShaper.Options options = new CorridorShaper.Options(4, 0.8);

        CorridorShaper.ShapedLine npc = new CorridorShaper(() -> 0L, null).shape(npcWorld, feet, indexes, options, true);
        CorridorShaper.ShapedLine player = new CorridorShaper(() -> 0L, null).shape(
            new WalkTerrainNavWorld(f.terrain(), MovementProfile.PLAYER, CellAccess.OPEN), feet, indexes, options, true);
        WalkPathShaper.Shaped walk = new WalkPathShaper().shape(new WalkPath(keys, new boolean[cells.length], 0, 0),
            f.terrain(), MovementProfile.PLAYER, CellAccess.OPEN, new WalkPathShaper.Options(4, 0.8, false));

        assertTrue(npc.waypoints().size() >= 2);
        assertEquals(npc.waypoints(), player.waypoints(), "the same shaper over both worlds");
        assertEquals(npc.waypoints().size(), walk.line().size(), "the walk path shaper gives that line");
        for (int i = 0; i < walk.line().size(); i++) {
            NavPoint p = npc.waypoints().get(i);
            assertArrayEquals(new double[] {p.x(), p.y() - 1, p.z()}, walk.line().get(i), 1e-12);
        }
        List<double[]> beforeTheEnd = walk.line().subList(0, walk.line().size() - 1); // the end is pinned beside it
        // the start's own place keeps 0.7 here (the window of its neighbours), the line no less within the tolerance
        assertTrue(least(samples(beforeTheEnd), (x, z) -> fromSquare(x, z, 0, 0, 6, 6)) >= 0.7 - SHAPER_TOLERANCE,
            "and it keeps the clearance from the building: " + show(walk.line()));
    }

    // ===== runs, joins, access =====

    @Test
    void aLadderEndsARunAndStaysWhereTheSearchPutIt() {
        // ground at 64 west of a wall at x = 6 (top 68), a ladder on its west face at x = 5, a roof east of it at 68
        WalkFixture f = new WalkFixture().floor(0, 0, 5, 6, 64, G).column(6, 0, 65, 68, GridFixture.STONE)
            .column(6, 1, 65, 68, GridFixture.STONE).column(6, 2, 65, 68, GridFixture.STONE)
            .column(6, 3, 65, 68, GridFixture.STONE).column(6, 4, 65, 68, GridFixture.STONE)
            .column(6, 5, 65, 68, GridFixture.STONE).column(6, 6, 65, 68, GridFixture.STONE)
            .column(5, 3, 65, 68, WalkFixture.LADDER).floor(7, 0, 12, 6, 68, G);
        WalkPath path = found(new WalkSearch().find(f.request(0, 64, 0, 10, 68, 3)));
        int firstLadder = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.isLadder(i) && firstLadder < 0) {
                firstLadder = i;
            }
        }
        assertTrue(firstLadder > 0, "the path climbs the ladder: " + path);

        WalkPathShaper.Shaped shaped = new WalkPathShaper().shape(path, f.terrain(), MovementProfile.PLAYER,
            CellAccess.OPEN, SHAPE);
        for (int i = 0; i < path.size(); i++) {
            if (path.isLadder(i)) {
                double[] p = path.points().get(i);
                assertTrue(contains(shaped.trail(), p), "ladder cell " + i + " kept: " + show(shaped.trail()));
            }
        }
        assertEquals(2, shaped.runs(), "the ground and the roof");
        assertArrayEquals(path.points().get(path.size() - 1), shaped.trail().get(shaped.trail().size() - 1), 1e-9);
    }

    @Test
    void aDropEndsARun() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 0, 67, G).floor(5, 0, 9, 0, 64, G);
        WalkPath path = found(new WalkSearch().find(f.request(0, 67, 0, 9, 64, 0)));
        assertEquals(5, WalkPathShaper.runEnd(path, 0), "the run ends before the drop: " + path);
        WalkPathShaper.Shaped shaped = new WalkPathShaper().shape(path, f.terrain(), MovementProfile.PLAYER,
            CellAccess.OPEN, SHAPE);
        assertEquals(2, shaped.runs());
        assertTrue(contains(shaped.line(), new double[] {4.5, 67, 0.5}), "the edge stays: " + show(shaped.line()));
        assertTrue(contains(shaped.line(), new double[] {5.5, 64, 0.5}), "the landing stays: " + show(shaped.line()));
    }

    @Test
    void theShapedLineNeverMovesOntoACellTheAccessBlocks() {
        // an open field; the mover may not enter x >= 6, the path runs along x = 5 from z = 3 to z = 9
        WalkFixture f = new WalkFixture().floor(0, 0, 12, 12, 64, G);
        CellAccess denied = (x, feetY, z) -> x >= 6 ? CellAccess.BLOCKED : 0.0;
        long[] keys = new long[7];
        for (int k = 0; k < 7; k++) {
            keys[k] = BlockKey.pack(5, 64, 3 + k);
        }
        WalkPathShaper.Shaped shaped = new WalkPathShaper().shape(new WalkPath(keys, new boolean[7], 6, 6),
            f.terrain(), MovementProfile.PLAYER, denied, SHAPE);
        assertEquals(6 - 0.8, shaped.line().get(0)[0], 1e-9, "the line moves off the region's border: " + show(shaped.line()));
        for (double[] p : samples(shaped.trail())) {
            assertTrue(p[0] <= 6 - CorridorShaper.HARD_MINIMUM + 1e-9, "never onto a denied cell: " + show(shaped.trail()));
        }
    }

    @Test
    void aSingleCellAndAStraightOpenPathStayAsTheyAre() {
        WalkFixture f = new WalkFixture().floor(0, 0, 12, 12, 64, G);
        WalkPath one = new WalkPath(new long[] {BlockKey.pack(6, 64, 6)}, new boolean[1], 0, 0);
        WalkPathShaper.Shaped single = new WalkPathShaper().shape(one, f.terrain(), MovementProfile.PLAYER,
            CellAccess.OPEN, SHAPE);
        assertEquals(1, single.trail().size());
        assertEquals(0, single.runs());

        WalkPath straight = found(new WalkSearch().find(f.request(2, 64, 6, 10, 64, 6)));
        WalkPathShaper.Shaped shaped = new WalkPathShaper(() -> 42L).shape(straight, f.terrain(), MovementProfile.PLAYER,
            CellAccess.OPEN, SHAPE);
        assertEquals(2, shaped.line().size(), "string pulled to its ends: " + show(shaped.line()));
        assertEquals(0, shaped.corners());
        assertEquals(0L, shaped.nanos(), "timed by the given clock");
        for (double[] p : shaped.line()) {
            assertEquals(6.5, p[2], 1e-9);
        }
    }

    @Test
    void optionsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new WalkPathShaper.Options(0.5, 0.8, true));
        assertThrows(IllegalArgumentException.class, () -> new WalkPathShaper.Options(8, 0.2, true));
        assertThrows(IllegalArgumentException.class, () -> new WalkPathShaper.Options(8, 3, true));
        assertEquals(WalkPathShaper.STRIP_HALF_WIDTH, WalkPathShaper.Options.of(0.8, true).halfWidth());
    }

    @Test
    void theAdapterReadsTheCapturedTerrainOnly() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 4, 64, G).block(2, 64, 2, GridFixture.SLAB)
            .column(3, 3, 65, 66, GridFixture.STONE).block(1, 65, 1, GridFixture.WATER).block(1, 66, 1, GridFixture.WATER);
        WalkTerrainNavWorld world = new WalkTerrainNavWorld(f.terrain(), MovementProfile.PLAYER, CellAccess.OPEN);

        assertTrue(world.isLoaded("any", 99, 99), "the capture is all there is");
        assertTrue(world.isStandableFloor("any", 0, 64, 0));
        assertFalse(world.isStandableFloor("any", 3, 64, 3), "no headroom under a wall");
        assertFalse(world.isStandableFloor("any", 1, 64, 1), "the head would be under water");
        assertFalse(world.isStandableFloor("any", 9, 64, 9), "outside the capture");
        assertEquals(3.0, world.freeHeight("any", 0, 64, 0), 1e-9);
        assertEquals(0.0, world.freeHeight("any", 3, 64, 3), 1e-9);
        assertTrue(world.regionsAt("any", 0, 65, 0).isEmpty());
        assertTrue(world.blocksBody("any", 3, 65, 3));
        assertFalse(world.blocksBody("any", 0, 65, 0));
        assertEquals(64.5, world.surfaceY("any", 2.5, 64, 2.5), 1e-9, "a slab's step");
        assertEquals(65.0, world.surfaceY("any", 0.5, 64, 0.5), 1e-9);
    }

    // ===== helpers =====

    private static WalkPath found(WalkResult result) {
        assertTrue(result.isFound(), result.toString());
        return result.path().orElseThrow();
    }

    private static double first(WalkPath path) {
        return path.floorY(0);
    }

    /** A polyline sampled every 0.1 blocks (horizontally), with its points. */
    static List<double[]> samples(List<double[]> line) {
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double len = Math.hypot(b[0] - a[0], b[2] - a[2]);
            int n = Math.max(1, (int) Math.ceil(len / 0.1));
            for (int k = 1; k <= n; k++) {
                double t = (double) k / n;
                out.add(new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    @FunctionalInterface
    interface Distance {
        double of(double x, double z);
    }

    private static double least(List<double[]> points, Distance distance) {
        double least = Double.MAX_VALUE;
        for (double[] p : points) {
            least = Math.min(least, distance.of(p[0], p[2]));
        }
        return least;
    }

    private static double fromSquare(double x, double z, double x0, double z0, double x1, double z1) {
        double dx = Math.max(Math.max(x0 - x, 0), x - x1);
        double dz = Math.max(Math.max(z0 - z, 0), z - z1);
        return Math.hypot(dx, dz);
    }

    private static boolean contains(List<double[]> line, double[] p) {
        for (double[] q : line) {
            if (Math.abs(q[0] - p[0]) < 1e-9 && Math.abs(q[1] - p[1]) < 1e-9 && Math.abs(q[2] - p[2]) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    private static String show(List<double[]> line) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : line) {
            sb.append(String.format("(%.2f,%.1f,%.2f) ", p[0], p[1], p[2]));
        }
        return sb.toString();
    }
}
