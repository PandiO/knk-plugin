package net.knightsandkings.knk.core.roads.route;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, O24): the default route mode reproduces today's road routing exactly.
 * {@code route-modes/road-default.txt} is {@link RouteModeScenes#transcript} written on the base branch ({@code next}
 * at {@code 3977329c}, before route modes existed); the request without a mode and the request in {@code preferred}
 * must give it character for character - every edge, length, cost (full precision), node, polyline and explanation.
 */
class RouteModeDefaultTranscriptTest {

    static String golden(String name) throws IOException {
        try (InputStream in = RouteModeDefaultTranscriptTest.class.getResourceAsStream("/route-modes/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDefaultModeIsTodaysRoadRouting() throws IOException {
        String today = golden("road-default.txt");
        assertEquals(today, RouteModeScenes.transcript(r -> r), "a request that names no mode");
        assertEquals(today, RouteModeScenes.transcript(r -> r.withMode(RouteMode.PREFERRED)), "preferred");
        assertEquals(today, RouteModeScenes.transcript(r -> r.withMode(RouteMode.DEFAULT)), "the default");
    }

    @Test
    void theOtherModesAreNotTodays() {
        // the transcript sees a change: the scenes carry multipliers and class costs
        String preferred = RouteModeScenes.transcript(r -> r);
        assertNotEquals(preferred, RouteModeScenes.transcript(r -> r.withMode(RouteMode.EFFICIENT)));
        assertNotEquals(preferred, RouteModeScenes.transcript(r -> r.withMode(RouteMode.SHORTEST)));
    }
}
