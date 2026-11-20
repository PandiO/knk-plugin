package net.knightsandkings.knk.core.production.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The CaptureRadius world task's input, radius check and output (PandiO/knk-workspace#679, PRODUCTION_KINDS § 3.3). */
class CaptureRadiusTest {

    @Test
    void theInputGivesAStartingRadiusAndTheCap() {
        assertEquals(new CaptureRadius.Input(32, 40), CaptureRadius.parseInput("{\"radius\":32,\"maxRadius\":40}"));
        assertEquals(new CaptureRadius.Input(null, CaptureRadius.DEFAULT_MAX_RADIUS), CaptureRadius.parseInput(null));
        assertEquals(new CaptureRadius.Input(null, CaptureRadius.DEFAULT_MAX_RADIUS), CaptureRadius.parseInput("{broken"));
    }

    @Test
    void theTypedRadiusIsAWholeNumberWithinTheCap() {
        assertTrue(CaptureRadius.refusal("12", 40).isEmpty());
        assertTrue(CaptureRadius.refusal("12 blocks", 40).isEmpty());
        assertEquals("The radius must be 1 to 40 blocks.", CaptureRadius.refusal("41", 40).orElseThrow());
        assertEquals("The radius must be a whole number of blocks.", CaptureRadius.refusal("big", 40).orElseThrow());
        assertNull(CaptureRadius.parseRadius("1.5"));
    }

    @Test
    void theOutputHasTheAreaFormsFields() {
        assertEquals("{\"shape\":\"Radius\",\"worldName\":\"world\",\"centerX\":10,\"centerY\":64,\"centerZ\":-5,\"radius\":24}",
                CaptureRadius.toJson(new CaptureRadius.Draft("world", 10, 64, -5, 24)));
    }
}
