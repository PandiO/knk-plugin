package net.knightsandkings.knk.core.roads.route;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** KNG-76: the road trail keeps to the middle of the road (and to the stairs on a slope). */
class TrailCentringTest {

    /** A fake road surface: road cells with their floor y; stair/slab cells. */
    private static final class Road implements TrailCentring.Ground {
        final Map<Long, Integer> floors = new HashMap<>();
        final Set<Long> stairs = new HashSet<>();

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        Road cells(int x0, int x1, int z0, int z1, int y) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    floors.put(key(x, z), y);
                }
            }
            return this;
        }

        Road stair(int x, int z) {
            stairs.add(key(x, z));
            return this;
        }

        @Override
        public OptionalInt roadFloor(int x, int z, int nearY) {
            Integer y = floors.get(key(x, z));
            return y != null && Math.abs(y - nearY) <= 1 ? OptionalInt.of(y) : OptionalInt.empty();
        }

        @Override
        public boolean stairOrSlab(int x, int y, int z) {
            return stairs.contains(key(x, z));
        }
    }

    /** Block-centred trail points along z = row, x from x0 to x1, every block, at floor y. */
    private static List<double[]> eastward(int x0, int x1, int row, double y) {
        List<double[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            out.add(new double[] {x + 0.5, y, row + 0.5});
        }
        return out;
    }

    @Test
    void onATwoBlockRoadTheTrailRunsBetweenTheTwoRows() {
        // the Brink stairs: rows z = 10 and 11, the skeleton (and the trail) on row 10
        Road road = new Road().cells(0, 20, 10, 11, 64);

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 10, 64), road);

        for (double[] p : centred.subList(3, centred.size() - 3)) {
            assertEquals(11.0, p[2], 1e-9, "z between the rows");
        }
        assertEquals(64, centred.get(5)[1], 1e-9, "y untouched");
    }

    @Test
    void aWideRoadIsLeftAlone() {
        Road road = new Road().cells(0, 20, 0, 20, 64);

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 10, 64), road);

        centred.forEach(p -> assertEquals(10.5, p[2], 1e-9));
    }

    @Test
    void aTrailAlongOneEdgeMovesToTheMiddle() {
        // a three-block road (rows 10-12) with the trail on row 10, as where a straight line cuts a bend
        Road road = new Road().cells(0, 20, 10, 12, 64);

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 10, 64), road);

        assertEquals(11.5, centred.get(8)[2], 1e-9);
    }

    @Test
    void aLedgeTwoBlocksUpOrDownIsNoRoad() {
        // rows 10-11 at y 64; row 9 is a ledge at y 66, row 12 a drop to y 62
        Road road = new Road().cells(0, 20, 10, 11, 64).cells(0, 20, 9, 9, 66).cells(0, 20, 12, 12, 62);

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 10, 64), road);

        assertEquals(11.0, centred.get(8)[2], 1e-9);
    }

    @Test
    void offTheRoadNothingMoves() {
        Road road = new Road().cells(0, 20, 20, 22, 64);

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 10, 64), road);

        centred.forEach(p -> assertEquals(10.5, p[2], 1e-9));
    }

    @Test
    void onASlopeTheTrailTakesTheStairs() {
        // a three-block road going down eastwards: stairs on row 12 only, full blocks on rows 10-11
        Road road = new Road();
        List<double[]> points = new ArrayList<>();
        for (int x = 0; x <= 20; x++) {
            int y = 70 - x / 2;
            road.cells(x, x, 10, 12, y).stair(x, 12);
            points.add(new double[] {x + 0.5, y, 11.5});
        }

        List<double[]> centred = TrailCentring.centre(points.subList(2, 19), road);

        assertEquals(12.5, centred.get(8)[2], 1e-9, "on the stairs");
    }

    @Test
    void theCellsAcrossTheRoadAreTheRoadsWidth() {
        // KNG-110: the live region tags look across the road the same way; rows 10-12, a ledge at row 9
        Road road = new Road().cells(0, 20, 10, 12, 64).cells(0, 20, 9, 9, 66);

        List<TrailCentring.Cell> cells = TrailCentring.across(new double[] {5.5, 64, 10.5}, new double[] {0, 1}, road);

        assertEquals(List.of(new TrailCentring.Cell(0, 5, 64, 10), new TrailCentring.Cell(1, 5, 64, 11),
            new TrailCentring.Cell(2, 5, 64, 12)), cells);
        assertEquals(List.of(), TrailCentring.across(new double[] {5.5, 64, 30.5}, new double[] {0, 1}, road), "off the road");
    }

    @Test
    void onTheFlatTheStairsDoNotPull() {
        Road road = new Road().cells(0, 20, 10, 12, 64);
        for (int x = 0; x <= 20; x++) {
            road.stair(x, 12);
        }

        List<double[]> centred = TrailCentring.centre(eastward(2, 18, 11, 64), road);

        assertEquals(11.5, centred.get(8)[2], 1e-9);
    }
}
