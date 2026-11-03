package net.knightsandkings.knk.core.survival.scaling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DESIGN §7 presets table (S29). */
class ScalingCurveTest {
    private static final double EPS = 1e-9;

    @Test
    void linear() {
        ScalingCurve c = ScalingCurve.linear(6, 2);
        assertEquals(6, c.value(1), EPS);
        assertEquals(8, c.value(2), EPS);
        assertEquals(24, c.value(10), EPS);
    }

    @Test
    void logarithmic() {
        ScalingCurve c = ScalingCurve.logarithmic(6, 4);
        assertEquals(6, c.value(1), EPS);
        assertEquals(6 + 4 * Math.log(10), c.value(10), EPS);
    }

    @Test
    void exponential() {
        ScalingCurve c = ScalingCurve.exponential(6, 1.5);
        assertEquals(6, c.value(1), EPS);
        assertEquals(9, c.value(2), EPS);
        assertEquals(6 * Math.pow(1.5, 9), c.value(10), EPS);
    }

    @Test
    void hybridFollowsCodZombiesHealth() {
        // +100 per round up to round 9, then ×1.1 per round (the design's shape example).
        ScalingCurve c = ScalingCurve.hybrid(150, 100, 9, 1.1);
        assertEquals(150, c.value(1), EPS);
        assertEquals(950, c.value(9), EPS);
        assertEquals(950 * 1.1, c.value(10), EPS);
        assertEquals(950 * Math.pow(1.1, 11), c.value(20), EPS);
    }

    @Test
    void statCurveHasBaseOneSoRoundOneIsExactlyOne() {
        for (ScalingCurve c : new ScalingCurve[] {
            ScalingCurve.linear(5, 0.5).asStatCurve(),
            ScalingCurve.logarithmic(5, 0.5).asStatCurve(),
            ScalingCurve.exponential(5, 1.2).asStatCurve(),
            ScalingCurve.hybrid(5, 0.5, 4, 1.2).asStatCurve()}) {
            assertEquals(1.0, c.value(1), 0.0, c.preset().name());
        }
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () -> ScalingCurve.hybrid(1, 1, 0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> ScalingCurve.linear(1, 1).value(0));
        assertThrows(IllegalArgumentException.class, () -> ScalingCurve.linear(Double.NaN, 1));
    }
}
