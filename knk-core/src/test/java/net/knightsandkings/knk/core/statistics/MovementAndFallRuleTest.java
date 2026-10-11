package net.knightsandkings.knk.core.statistics;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN.md §F.9: movement modes with the max-segment rule; highest survived fall. */
class MovementAndFallRuleTest {

    @Test
    void vehicleWinsOverFlyingAndSwimming() {
        assertEquals(MovementClassifier.Mode.VEHICLE, MovementClassifier.classify(true, true, true, true, 1.0, 10));
    }

    @Test
    void glidingOrFlyingIsFlying() {
        assertEquals(MovementClassifier.Mode.FLYING, MovementClassifier.classify(false, true, false, false, 1.0, 10));
        assertEquals(MovementClassifier.Mode.FLYING, MovementClassifier.classify(false, false, true, true, 1.0, 10));
    }

    @Test
    void swimmingIsFootPlusSwim() {
        MovementClassifier.Mode mode = MovementClassifier.classify(false, false, false, true, 1.0, 10);
        assertEquals(MovementClassifier.Mode.SWIM, mode);
        assertEquals(StatisticsMetric.DISTANCE_FOOT, mode.metric());
        assertEquals(MovementClassifier.Mode.FOOT, MovementClassifier.classify(false, false, false, false, 1.0, 10));
    }

    @Test
    void segmentsOutsideTheLimitsDoNotCount() {
        assertNull(MovementClassifier.classify(false, false, false, false, 0.0, 10));
        assertNull(MovementClassifier.classify(false, false, false, false, -1.0, 10));
        assertNull(MovementClassifier.classify(false, false, false, false, Double.NaN, 10));
        assertNull(MovementClassifier.classify(false, false, false, false, 10.01, 10));
        assertEquals(MovementClassifier.Mode.FOOT, MovementClassifier.classify(false, false, false, false, 10.0, 10));
    }

    @Test
    void lengthIsEuclidean() {
        assertEquals(5.0, MovementClassifier.length(3, 0, 4), 1e-9);
        assertEquals(Math.sqrt(3), MovementClassifier.length(1, -1, 1), 1e-9);
    }

    @Test
    void aSurvivedDamagingFallIsRoundedToOneDecimal() {
        assertEquals(OptionalDouble.of(23.5), FallRule.survivedFall(23.45, 20, 19.9, 0));
        assertEquals(OptionalDouble.of(23.4), FallRule.survivedFall(23.44, 20, 5, 0));
    }

    @Test
    void absorptionCountsTowardsSurviving() {
        assertTrue(FallRule.survivedFall(40, 4, 6, 4).isPresent());
        assertTrue(FallRule.survivedFall(40, 4, 6, 0).isEmpty());
    }

    @Test
    void fatalHarmlessOrZeroFallsDoNotCount() {
        assertTrue(FallRule.survivedFall(30, 10, 10, 0).isEmpty(), "fatal");
        assertTrue(FallRule.survivedFall(30, 10, 0, 0).isEmpty(), "no damage");
        assertTrue(FallRule.survivedFall(0, 10, 1, 0).isEmpty(), "no distance");
        assertTrue(FallRule.survivedFall(0.04, 10, 1, 0).isEmpty(), "rounds to zero");
    }
}
