package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurveySamplingGateTest {

    private static SurveySamplingGate.PlayerState state(boolean onGround, boolean flying, boolean gliding, boolean vehicle,
                                                        boolean swimming, boolean inWater, double dx, double dz) {
        return new SurveySamplingGate.PlayerState(onGround, flying, gliding, vehicle, swimming, inWater, dx, dz);
    }

    @Test
    void onlyAWalkingGroundedPlayerSamples() {
        assertTrue(SurveySamplingGate.shouldSample(state(true, false, false, false, false, false, 0.2, 0.1)));
        assertEquals(SurveySamplingGate.Verdict.FLYING, SurveySamplingGate.verdict(state(false, true, false, false, false, false, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.FLYING, SurveySamplingGate.verdict(state(false, false, true, false, false, false, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.RIDING, SurveySamplingGate.verdict(state(true, false, false, true, false, false, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.SWIMMING, SurveySamplingGate.verdict(state(true, false, false, false, true, false, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.SWIMMING, SurveySamplingGate.verdict(state(true, false, false, false, false, true, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.NOT_ON_GROUND, SurveySamplingGate.verdict(state(false, false, false, false, false, false, 0.5, 0)));
        assertEquals(SurveySamplingGate.Verdict.STANDING_STILL, SurveySamplingGate.verdict(state(true, false, false, false, false, false, 0.05, 0.05)));
        assertFalse(SurveySamplingGate.shouldSample(state(true, false, false, false, false, false, 0, 0)));
    }

    @Test
    void directionComesFromMovementElseYaw() {
        assertArrayEquals(new double[] {1, 0}, SurveySamplingGate.direction(2, 0, 90f), 1e-9);
        double[] fromYaw = SurveySamplingGate.direction(0.01, 0, 0f);
        assertArrayEquals(new double[] {0, 1}, fromYaw, 1e-9); // yaw 0 faces +z
        double[] west = SurveySamplingGate.direction(0, 0, 90f);
        assertArrayEquals(new double[] {-1, 0}, west, 1e-9);   // yaw 90 faces −x
        assertArrayEquals(new double[] {-1, 0}, SurveySamplingGate.lateral(new double[] {0, 1}), 1e-9);
    }
}
