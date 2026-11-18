package net.knightsandkings.knk.core.navigation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.GridWorld;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;

/**
 * The plaza lines on a route's trail (off-road navigation phase 7 step 2, A22, A25; PandiO/knk-workspace#712): the
 * spots inside a crossing are moved onto its line by their share of the way, the others stay; the crossings are worked
 * out lazily, one per redraw, when the window nears them; a crossing's line is placed by the route's own measure.
 */
class PlazaTrailTest {

    /** A straight line along x at z 0 from 10 to 20 and a bent line between the same ends. */
    private static PlazaTrail.Segment bend() {
        return new PlazaTrail.Segment(10, 20, List.of(new double[] {10.5, 64, 0.5}, new double[] {15.5, 64, 3.5},
            new double[] {20.5, 64, 0.5}));
    }

    private static List<double[]> spots(double... alongs) {
        List<double[]> out = new ArrayList<>();
        for (double a : alongs) {
            out.add(new double[] {a + 0.5, 64, 0.5});
        }
        return out;
    }

    @Test
    void withoutLinesTheSpotsAreDrawnAsTheyAre() {
        List<double[]> s = spots(0, 1.5, 3);
        assertSame(s, new PlazaTrail(List.of()).draw(s, new double[] {0, 1.5, 3}));
    }

    @Test
    void spotsInsideACrossingMoveOntoItsLineByTheirShareOfTheWay() {
        PlazaTrail trail = new PlazaTrail(List.of(new PlazaTrail.Crossing(1, 15)));
        trail.prepare(0, 30, c -> bend());
        double[] alongs = {7, 10, 15, 20, 23};
        List<double[]> drawn = trail.draw(spots(alongs), alongs);

        assertArrayEquals(new double[] {7.5, 64, 0.5}, drawn.get(0), 1e-9, "before the plaza: as it was");
        assertArrayEquals(new double[] {10.5, 64, 0.5}, drawn.get(1), 1e-9, "the entry");
        assertArrayEquals(new double[] {15.5, 64, 3.5}, drawn.get(2), 1e-9, "halfway: the line's middle");
        assertArrayEquals(new double[] {20.5, 64, 0.5}, drawn.get(3), 1e-9, "the exit");
        assertArrayEquals(new double[] {23.5, 64, 0.5}, drawn.get(4), 1e-9, "after: as it was");
        assertThrows(IllegalArgumentException.class, () -> trail.draw(spots(1), new double[] {1, 2}));
    }

    @Test
    void theSameAlongIsAlwaysTheSamePointSoParticlesStayPut() {
        PlazaTrail trail = new PlazaTrail(List.of(new PlazaTrail.Crossing(1, 15)));
        trail.prepare(0, 30, c -> bend());
        double[] a = {12, 13.5};
        assertArrayEquals(trail.draw(spots(a), a).get(1), trail.draw(spots(13.5), new double[] {13.5}).get(0), 1e-12);
    }

    @Test
    void crossingsAreWorkedOutOneAtATimeWhenTheWindowNearsThem() {
        PlazaTrail trail = new PlazaTrail(List.of(new PlazaTrail.Crossing(1, 40), new PlazaTrail.Crossing(2, 200)));
        List<Integer> asked = new ArrayList<>();

        assertFalse(trail.prepare(0, 10, c -> {
            asked.add(c.nodeId());
            return null;
        }), "40 is beyond the window plus the look-ahead (10 + 24)");
        assertTrue(trail.prepare(10, 10, c -> {
            asked.add(c.nodeId());
            return null;
        }));
        assertFalse(trail.prepare(10, 10, c -> {
            asked.add(c.nodeId());
            return null;
        }), "the first crossing is done, the second far");
        assertEquals(List.of(1), asked);
        assertEquals(1, trail.computed());
        assertTrue(trail.segments().isEmpty(), "no line there: the route's bend stays");

        trail.prepare(190, 10, c -> bend());
        assertEquals(2, trail.computed());
        assertEquals(1, trail.segments().size());
    }

    @Test
    void aSegmentNeedsALineAfterItsStart() {
        assertThrows(IllegalArgumentException.class, () -> new PlazaTrail.Segment(5, 5, bend().points()));
        assertThrows(IllegalArgumentException.class, () -> new PlazaTrail.Segment(5, 6, List.of(new double[] {0, 0, 0})));
    }

    @Test
    void aHorizontalDistanceBecomesTheRoutesOwn3dMeasure() {
        // 3 flat, then 4 across and 3 up (5 long)
        List<double[]> line = List.of(new double[] {0, 64, 0}, new double[] {3, 64, 0}, new double[] {7, 67, 0});
        assertEquals(0, PlazaTrail.along3d(line, 0), 1e-9);
        assertEquals(3, PlazaTrail.along3d(line, 3), 1e-9);
        assertEquals(5.5, PlazaTrail.along3d(line, 5), 1e-9);
        assertEquals(8, PlazaTrail.along3d(line, 7), 1e-9);
        assertEquals(8, PlazaTrail.along3d(line, 99), 1e-9);
    }

    @Test
    void aPlazaCrossingIsPlacedOnTheRoutePolylineByItsEntryAndExit() {
        GridWorld world = new GridWorld();
        world.walls(7, 7, 23, 7).walls(7, 23, 23, 23).walls(7, 7, 7, 23).walls(23, 7, 23, 23);
        for (int a = 14; a <= 16; a++) {
            world.walls.remove(GridWorld.key(a, 7));
            world.walls.remove(GridWorld.key(7, a));
        }
        world.walls(13, -6, 13, 6).walls(17, -6, 17, 6).walls(-6, 13, 6, 13).walls(-6, 17, 6, 17);
        // the route as the router gives it: floor blocks, west road to the node at block 15 15, then north
        List<double[]> polyline = new ArrayList<>();
        for (int x = -5; x <= 15; x++) {
            polyline.add(new double[] {x, 64, 15});
        }
        for (int z = 14; z >= -5; z--) {
            polyline.add(new double[] {15, 64, z});
        }
        PlazaCurve.Result result = PlazaCurve.cross(world, PlazaLinePreviews.feetOf(GridWorld.W, polyline),
            new PlazaCrossing(new NavPoint(GridWorld.W, 15, 64, 15), 7), PlazaCurve.Options.defaults(4, 0.8));
        PlazaTrail.Segment segment = PlazaTrail.segmentOf(polyline, result);

        assertEquals(PlazaCurve.Kind.CURVE, result.kind(), result.curveFailure());
        assertEquals(13, segment.from(), 1e-9, "the entry, 13 blocks along the route");
        assertEquals(27, segment.to(), 1e-9, "the exit, the right angle's 14 blocks on");
        assertArrayEquals(new double[] {8.5, 64, 15.5}, segment.points().get(0), 1e-9);
        assertArrayEquals(new double[] {15.5, 64, 8.5}, segment.points().get(segment.points().size() - 1), 1e-9);
    }

    @Test
    void aCrossingWithoutALineHasNoSegment() {
        PlazaCurve.Result street = new PlazaCurve.Result(PlazaCurve.Kind.STREET, List.of(), List.of(),
            new NavPoint(GridWorld.W, 0, 64, 0), 0, -1, -1, 0, 0, 0, 0, null, 0);
        assertNull(PlazaTrail.segmentOf(List.of(new double[] {0, 64, 0}, new double[] {1, 64, 0}), street));
    }
}
