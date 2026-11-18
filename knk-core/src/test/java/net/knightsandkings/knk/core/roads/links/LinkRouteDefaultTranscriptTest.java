package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.RouteMode;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, O24): with walk links off and on, the default route mode reproduces
 * today's routes exactly. {@code route-modes/links-default.txt} is {@link LinkRouteTranscript#transcript} written on the
 * base branch ({@code next} at {@code 3977329c}) with today's planner constructor.
 */
class LinkRouteDefaultTranscriptTest {

    private static String golden() throws IOException {
        try (InputStream in = LinkRouteDefaultTranscriptTest.class.getResourceAsStream("/route-modes/links-default.txt")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDefaultModeIsTodaysRoutingWithWalkLinksOffAndOn() throws IOException {
        String today = golden();
        assertEquals(today, LinkRouteTranscript.transcript(r -> r, WalkLinkPlanner::new), "no mode named anywhere");
        assertEquals(today, LinkRouteTranscript.transcript(r -> r.withMode(RouteMode.PREFERRED),
            (candidates, cache, world, mover, policy, minFactor) -> new WalkLinkPlanner(candidates, cache, world, mover,
                policy, minFactor, RouteMode.PREFERRED)), "preferred, as NavigationService passes it");
    }
}
