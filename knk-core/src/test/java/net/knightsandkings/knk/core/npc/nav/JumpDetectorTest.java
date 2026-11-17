package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Jumps told by upward speed, not by the rise per tick (link B4, PandiO/knk-workspace#673). */
class JumpDetectorTest {

    /** Feed y, on ground, speed per tick; how many jumps start. */
    private static int jumps(JumpDetector d, double[][] ticks) {
        int n = 0;
        for (double[] t : ticks) {
            n += d.sample(t[0], t[1] > 0, t[2]) ? 1 : 0;
        }
        return n;
    }

    @Test
    void aJumpIsCountedOnceByItsTakeOffSpeed() {
        JumpDetector d = new JumpDetector();
        // vanilla jump: 0.42 up, then 0.33, 0.25 ... down again and landed a block higher
        int n = jumps(d, new double[][] {
            {72, 1, -0.08}, {72.42, 0, 0.42}, {72.75, 0, 0.33}, {73.0, 0, 0.25}, {73.17, 0, 0.17},
            {73.1, 0, -0.08}, {73.0, 1, -0.08}, {73.0, 1, -0.08}});

        assertEquals(1, n);
        assertFalse(d.inJump(), "landed");
    }

    @Test
    void aHalfBlockStepUpIsNoJump() {
        JumpDetector d = new JumpDetector();
        // a slab step: the feet rise 0.5 in one tick, on the ground, with the gravity tick's speed
        int n = jumps(d, new double[][] {
            {72, 1, -0.08}, {72.5, 1, -0.08}, {72.5, 1, -0.08}, {73.0, 1, -0.08}, {73.5, 1, -0.08}});

        assertEquals(0, n, "the trace's old rule (dy > 0.2) counted three");
    }

    @Test
    void withoutTheEnginesSpeedTheRiseOffTheGroundCounts() {
        JumpDetector d = new JumpDetector();
        double nan = Double.NaN;
        int steps = jumps(d, new double[][] {{72, 1, nan}, {72.5, 1, nan}, {73.0, 1, nan}});
        int jump = jumps(d, new double[][] {{73.42, 0, nan}, {73.75, 0, nan}, {74.0, 0, nan}, {74.0, 1, nan}});

        assertEquals(0, steps, "steps up on the ground");
        assertEquals(1, jump);
    }

    @Test
    void twoJumpsInARowAreTwo() {
        JumpDetector d = new JumpDetector();
        double[][] one = {{72, 1, -0.08}, {72.42, 0, 0.42}, {72.75, 0, 0.33}, {73, 1, -0.08}};

        assertEquals(1, jumps(d, one));
        assertEquals(1, jumps(d, one));
    }

    @Test
    void aFallIsNoJump() {
        JumpDetector d = new JumpDetector();

        assertEquals(0, jumps(d, new double[][] {{72, 1, -0.08}, {71.9, 0, -0.16}, {71.5, 0, -0.3}, {70, 1, -0.08}}));
        assertTrue(!d.inJump());
    }
}
