package net.knightsandkings.knk.core.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The join point with the least remaining cost (off-road DESIGN § 7.5.4, A19). */
class JoinPointTest {

    /** A straight road east along z = 0 from x = 0 to x = 100, floor 64. */
    private static final List<double[]> ROAD = List.of(new double[] {0, 64, 0}, new double[] {100, 64, 0});

    @Test
    void aPlayerBesideTheRoadJoinsAheadOfTheirNearestPoint() {
        // 10 beside the road at x = 50: cost 1.5 * sqrt(100 + d^2) + 50 - d is least at d = 10 / sqrt(1.25) = 8.9
        JoinPoint.Choice c = JoinPoint.choose(ROAD, 50, 64, 10, 96);
        assertEquals(59, c.along(), 1.0);
        assertTrue(c.cost() < 1.5 * 10 + 50, "cheaper than walking straight to the nearest point");
    }

    @Test
    void aPlayerWhoWalkedBackPastAJoinPointIsGuidedForwardsToIt() {
        // 3 beside the road at x = 20: forwards (about x = 22.7), not back to the start nor to the nearest point
        JoinPoint.Choice c = JoinPoint.choose(ROAD, 20, 64, 3, 96);
        assertTrue(c.along() > 20 && c.along() < 26, "joins just ahead: " + c.along());
    }

    @Test
    void aPlayerBehindTheStartJoinsAtTheStartSideOfTheRoad() {
        // 20 blocks behind the start, on its line: every point costs 1.5 * (20 + d) + 100 - d, least at d = 0
        JoinPoint.Choice c = JoinPoint.choose(ROAD, -20, 64, 0, 96);
        assertEquals(0, c.along(), 1e-9);
    }

    @Test
    void theRoadBehindThePlayerCanBeTheBetterJoin() {
        // a route that runs east 50 and back west 50 along z = 6: a player at (10, 3) is nearer the second leg's end
        List<double[]> uTurn = List.of(new double[] {0, 64, 0}, new double[] {50, 64, 0}, new double[] {50, 64, 6},
            new double[] {0, 64, 6});
        JoinPoint.Choice c = JoinPoint.choose(uTurn, 10, 64, 3, 96);
        assertTrue(c.along() > 56 + 30, "on the way back, not the first pass: " + c.along());
    }

    @Test
    void onlyPointsWithinTheWalkRangeAreConsidered() {
        JoinPoint.Choice c = JoinPoint.choose(ROAD, 50, 64, 10, 12);
        assertTrue(PolylineCursor.distance(c.point(), 50, 64, 10) <= 12 + 1e-9);
        JoinPoint.Choice none = JoinPoint.choose(ROAD, 50, 64, 200, 96);
        assertEquals(0, none.along(), 1e-9, "nothing in range: the route's start");
    }
}
