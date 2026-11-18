package net.knightsandkings.knk.core.roads.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.RouteMode;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, O24): walk legs in the default route mode are today's exactly.
 * {@code route-modes/walk-default.txt} is {@link WalkLegTranscript#transcript} written on the base branch ({@code next}
 * at {@code 3977329c}).
 */
class WalkLegDefaultTranscriptTest {

    private static String golden() throws IOException {
        try (InputStream in = WalkLegDefaultTranscriptTest.class.getResourceAsStream("/route-modes/walk-default.txt")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDefaultModeIsTodaysWalkLegs() throws IOException {
        String today = golden();
        assertEquals(today, WalkLegTranscript.transcript(p -> p));
        assertEquals(today, WalkLegTranscript.transcript(p -> p.forMode(RouteMode.PREFERRED)));
        assertEquals(today, WalkLegTranscript.transcript(p -> p.forMode(RouteMode.EFFICIENT)),
            "efficient keeps the walk profile too (no landmark weights before #709)");
        assertNotEquals(today, WalkLegTranscript.transcript(p -> p.forMode(RouteMode.SHORTEST)));
    }
}
