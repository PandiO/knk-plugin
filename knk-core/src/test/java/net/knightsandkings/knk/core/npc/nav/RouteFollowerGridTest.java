package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The route line on a fixed grid (PandiO/knk-workspace#958): the same road gives the same line wherever it lies. The
 * paper-boot replay {@code npc-replay-quarry-to-keep} failed with its scene at world x 32752 and passed at 32740: its
 * road runs along the block face x 1412 (real), and after the move into the scene the line's points there came out a
 * hair either side of that face, depending on whether they lay below or above x 32768, where a double loses a bit of
 * its fraction.
 */
class RouteFollowerGridTest {

    /** The scene offsets of the two runs: origin x 32740 (passed) and 32752 (failed), less the box's real x 1391. */
    private static final double PASSED = 32740 - 1391;
    private static final double FAILED = 32752 - 1391;
    /** The replay's z offset (origin z 30000, the box's real z -559). */
    private static final double Z = 30000 + 559;

    /** The leader's road round x 1412 in the snapshot (real floor points): a diagonal, then along the face x 1412. */
    private static List<NavPoint> realRoad() {
        return List.of(floor(1419.8, 48, -517.6), floor(1418.0, 48, -520.0), floor(1412.0, 48, -513.0),
            floor(1412.0, 48, -498.8302), floor(1411.9501, 50, -494.8823), floor(1409.3, 51, -490.7));
    }

    private static List<NavPoint> moved(List<NavPoint> line, double dx) {
        List<NavPoint> out = new ArrayList<>();
        for (NavPoint p : line) {
            out.add(floor(p.x() + dx, p.y(), p.z() + Z));
        }
        return out;
    }

    @Test
    void theLineIsTheSameAtBothSceneOffsets() {
        List<NavPoint> passed = new RouteFollower(moved(realRoad(), PASSED)).line();
        List<NavPoint> failed = new RouteFollower(moved(realRoad(), FAILED)).line();

        assertEquals(passed.size(), failed.size());
        for (int i = 0; i < passed.size(); i++) {
            NavPoint a = passed.get(i);
            NavPoint b = failed.get(i);
            // exactly, not within a tolerance: a hair across a block face is the bug
            assertEquals(a.x() - PASSED, b.x() - FAILED, 0.0, "x of point " + i + ": " + a + " / " + b);
            assertEquals(a.y(), b.y(), 0.0, "y of point " + i);
            assertEquals(a.z(), b.z(), 0.0, "z of point " + i);
        }
    }

    @Test
    void theLineIsTheSameWithWalkedPointsToo() {
        boolean[] walked = {false, false, true, true, true, false};
        List<NavPoint> passed = new RouteFollower(moved(realRoad(), PASSED), List.of(), walked).line();
        List<NavPoint> failed = new RouteFollower(moved(realRoad(), FAILED), List.of(), walked).line();

        assertEquals(passed.size(), failed.size());
        for (int i = 0; i < passed.size(); i++) {
            assertEquals(passed.get(i).x() - PASSED, failed.get(i).x() - FAILED, 0.0, "x of point " + i);
            assertEquals(passed.get(i).z(), failed.get(i).z(), 0.0, "z of point " + i);
        }
    }

    @Test
    void aPointOnTheFaceStaysInItsBlockAtEveryOffset() {
        // a resampled point on the face x 1412 (real) is in block 1412 wherever the road lies
        for (double dx : new double[] {0, PASSED, FAILED, 1 << 20, 29_998_000 - 1412}) {
            for (NavPoint p : new RouteFollower(moved(realRoad(), dx)).line()) {
                double real = p.x() - dx;
                if (Math.abs(real - 1412.0) < 1e-3) {
                    assertEquals(1412.0, real, 0.0, "on the face at offset " + dx + ": " + p);
                    assertEquals((long) (1412 + dx), p.blockX(), "its block at offset " + dx);
                }
            }
        }
    }

    @Test
    void theGridIsFineAndExact() {
        NavPoint p = floor(32778.349208626543, 112, 30039.759256602367);
        NavPoint q = RouteFollower.onGrid(p);

        assertEquals(p.x(), q.x(), 0.5 / RouteFollower.LINE_GRID);
        assertEquals(p.z(), q.z(), 0.5 / RouteFollower.LINE_GRID);
        assertEquals(p.y(), q.y(), 0.0);
        assertEquals(q.x(), Math.rint(q.x() * RouteFollower.LINE_GRID) / RouteFollower.LINE_GRID, 0.0);
        // a point already on the grid is kept as it is
        NavPoint centre = floor(1412.5, 48, -513.5);
        assertSame(centre, RouteFollower.onGrid(centre));
        // on the grid at the world border too: moving it by whole blocks keeps it there
        NavPoint far = RouteFollower.onGrid(floor(29_999_999.123456789, 64, -29_999_999.987654321));
        assertTrue(RouteFollower.onGrid(floor(far.x() - 29_000_000, 64, far.z())).x() == far.x() - 29_000_000);
    }
}
