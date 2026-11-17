package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/** Footsteps for the back-off and the fall check (implementation plan 4b.2 items 6.1 and 12). */
class RecentStepsTest {

    private final RecentSteps steps = new RecentSteps();

    private static NavPoint feet(double x, double y) {
        return new NavPoint(WORLD, x, y, 0.5);
    }

    @Test
    void theWayBackRetracesTheLastFootstepsTwoBlocks() {
        for (int i = 0; i < 12; i++) {
            steps.record(feet(0.5 + i, 65), true);
        }

        List<NavPoint> back = steps.wayBack(feet(11.5, 65), 2);

        assertEquals(RecentSteps.KEEP, steps.size(), "only the last eight are kept");
        assertEquals(List.of(feet(10.5, 65), feet(9.5, 65)), back);
    }

    @Test
    void noWayBackWithoutFootstepsOrAfterAFall() {
        assertTrue(steps.wayBack(feet(0.5, 65), 2).isEmpty());

        steps.record(feet(0.5, 70), true);
        steps.record(feet(1.5, 70), true);
        steps.record(feet(2.5, 69), false); // off the ledge
        double fell = steps.record(feet(2.5, 65), true);

        assertEquals(5, fell, 1e-9, "a fall of five blocks");
        assertTrue(steps.wayBack(feet(2.5, 65), 2).isEmpty(), "the footsteps up there lead nowhere now");
    }

    @Test
    void walkingDownStairsIsNoFall() {
        double fell = 0;
        for (int i = 0; i < 10; i++) {
            steps.record(feet(0.5 + i, 70 - i), i % 2 == 0);
            fell = Math.max(fell, steps.record(feet(0.6 + i, 70 - i), true));
        }

        assertEquals(0, fell, 1e-9);
    }

    @Test
    void withTheEnginesFallDistanceStairsBetweenTwoLooksAreNoFallARealDropIs() {
        steps.record(feet(0.5, 70), true, 0);
        steps.record(feet(1.5, 69), false, 0.4); // three stair steps between two looks, each a short hop
        steps.record(feet(2.5, 68), false, 0.6);
        assertEquals(0, steps.record(feet(3.5, 67), true, 0), 1e-9);

        steps.record(feet(4.5, 67), false, 0.3); // off a ledge
        steps.record(feet(4.5, 65), false, 1.9);
        assertEquals(4, steps.record(feet(4.5, 63), true, 0), 1e-9);
    }

    @Test
    void aBodyWhoseEngineFallDistanceStaysZeroIsSeenFallingByItsSpeed() {
        // a Citizens player NPC: fall distance near 0 all the way down (#592: 7-block falls counted as none)
        steps.record(feet(0.5, 74), true, 0, 0);
        steps.record(feet(1.5, 73.9), false, 0, -0.08);
        steps.record(feet(1.6, 71.5), false, 0, -0.62);
        assertEquals(7, steps.record(feet(1.7, 67), true, 0, 0), 1e-9);
    }

    @Test
    void stepsDownAStaircaseNeverGetFastEnoughToBeAFall() {
        steps.record(feet(0.5, 70), true, 0, 0);
        steps.record(feet(1.5, 69), false, 0, -0.32); // one-block steps, airborne at most looks
        steps.record(feet(2.5, 68), false, 0, -0.40);
        steps.record(feet(3.5, 67.4), false, 0, -0.30);
        assertEquals(0, steps.record(feet(4.5, 66), true, 0, 0), 1e-9);
    }

    @Test
    void aPushedBodysOldFootstepsAreNoWayBack() {
        for (int i = 0; i < 6; i++) {
            steps.record(feet(10.5 + i, 65), true);
        }
        steps.record(new NavPoint(WORLD, 13.5, 65, 3.5), true); // pushed three blocks aside into a pocket

        assertTrue(steps.wayBack(new NavPoint(WORLD, 13.5, 65, 3.5), 2).isEmpty());
    }
}
