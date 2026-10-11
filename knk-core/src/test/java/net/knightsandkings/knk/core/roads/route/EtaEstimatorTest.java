package net.knightsandkings.knk.core.roads.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtaEstimatorTest {

    private final EtaEstimator eta = EtaEstimator.defaults();

    @Test
    void sprintSpeedGivesThreeMinutesPerThousandBlocks() {
        assertEquals(5.6, eta.sprintSpeed());
        assertEquals(178.6, eta.seconds(1000), 0.1);
        assertEquals("~3 min", EtaEstimator.formatSeconds(eta.seconds(1000)));
        assertEquals(0, eta.seconds(-5));
    }

    @Test
    void formatting() {
        assertEquals("~55 s", EtaEstimator.formatSeconds(53.6));
        assertEquals("~5 s", EtaEstimator.formatSeconds(0));
        assertEquals("~1 min", EtaEstimator.formatSeconds(58));
        assertEquals("~1 min", EtaEstimator.formatSeconds(80));
        assertEquals("~2 min", EtaEstimator.formatSeconds(100));
        assertEquals("340 m", EtaEstimator.formatDistance(340.4));
        assertEquals("1.2 km", EtaEstimator.formatDistance(1234));
        assertEquals("340 m · ~1 min", eta.describe(340));
        assertThrows(IllegalArgumentException.class, () -> new EtaEstimator(0));
    }
}
