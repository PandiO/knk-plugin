package net.knightsandkings.knk.core.regions.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.geometry.ConvexHull2D.Point;

/** KNG-257: the hull that turns a round selection into a WorldGuard polygon. */
class ConvexHull2DTest {

    @Test
    void aSquareWithInnerAndEdgePointsKeepsItsFourCorners() {
        List<Point> hull = ConvexHull2D.of(List.of(new Point(0, 0), new Point(4, 0), new Point(4, 4), new Point(0, 4),
            new Point(2, 2), new Point(2, 0), new Point(0, 0)));

        assertEquals(List.of(new Point(0, 0), new Point(4, 0), new Point(4, 4), new Point(0, 4)), hull);
    }

    @Test
    void fewerThanThreeDistinctPointsComeBackAsTheyAre() {
        assertEquals(List.of(new Point(1, 1)), ConvexHull2D.of(List.of(new Point(1, 1), new Point(1, 1))));
        assertEquals(List.of(new Point(0, 0), new Point(3, 0)), ConvexHull2D.of(List.of(new Point(3, 0), new Point(0, 0))));
    }

    @Test
    void theRowEndsOfADiscGiveASymmetricPolygonAroundItsCentre() {
        List<Point> rowEnds = new ArrayList<>();
        int r = 6;
        for (int z = -r; z <= r; z++) {
            int half = (int) Math.floor(Math.sqrt((r + 0.5) * (r + 0.5) - z * z));
            rowEnds.add(new Point(-half, z));
            rowEnds.add(new Point(half, z));
        }

        List<Point> hull = ConvexHull2D.of(rowEnds);

        assertTrue(hull.size() >= 8, "a disc needs more than a box: " + hull);
        for (Point p : hull) {
            assertTrue(hull.contains(new Point(-p.x(), p.z())) && hull.contains(new Point(p.x(), -p.z())), "symmetric: " + hull);
        }
    }
}
