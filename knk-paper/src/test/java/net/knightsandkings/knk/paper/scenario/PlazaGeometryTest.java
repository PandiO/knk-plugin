package net.knightsandkings.knk.paper.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The plaza scenario's reference line (link M5): the shortest way that keeps a clearance from the well's box. */
class PlazaGeometryTest {

    @Test
    void aClearChordIsTheStraightLine() {
        assertEquals(10.0, PlazaGeometry.shortestRound(0, 0, 10, 0, 4, 3, 6, 5, 0.8), 1e-9);
    }

    @Test
    void aBoxOnTheChordIsGoneRoundByItsCorners() {
        // round the corners (4, -1) and (6, -1): 2 × √17 + 2 (a hair of clearance)
        assertEquals(2 * Math.sqrt(17) + 2, PlazaGeometry.shortestRound(0, 0, 10, 0, 4, -1, 6, 1, 0.01), 0.03);
    }

    @Test
    void moreClearanceIsALongerWayRoundButNeverInsideIt() {
        double tight = PlazaGeometry.shortestRound(0, 0, 10, 0, 4, -1, 6, 1, 0.01);
        double clear = PlazaGeometry.shortestRound(0, 0, 10, 0, 4, -1, 6, 1, 0.8);
        assertTrue(clear > tight && clear < tight + 2 * 0.8 + 0.5, tight + " < " + clear);
        assertEquals(0.8, PlazaGeometry.toBox(4 - 0.8, 0, 4, -1, 6, 1), 1e-9);
    }
}
