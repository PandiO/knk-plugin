package net.knightsandkings.knk.paper.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;
import net.knightsandkings.knk.paper.navigation.NavigationDomainChanges;

/**
 * A16 (landmark-domain DESIGN § 7.2 item 2, LD22, PandiO/knk-workspace#985): an {@code EntityChanged} of type
 * {@code Landmark} reaches the domain catalogue, the region cache and, once per burst, the navigation model and the
 * landmark message settings.
 */
class LandmarkRealtimeTest {

    private final List<String> calls = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Coalescer coalescer = new Coalescer((delay, action) -> scheduled.add(action), Duration.ofSeconds(2));
    private final EntityChangeRouter router = new EntityChangeRouter();

    private void register() {
        LandmarkRealtime.register(router, coalescer, id -> calls.add("catalogue " + id), () -> calls.add("catalogue all"),
            () -> calls.add("region cache"), () -> calls.add("model"), () -> calls.add("messages"));
    }

    @Test
    void aLandmarkChange_ClearsTheCaches_AndRefreshesTheModelOncePerBurst() {
        register();

        assertTrue(router.route("Landmark", 41, "domain_41", "Updated"));
        router.route("Landmark", 42, null, "Created");
        assertEquals(List.of("catalogue 41", "region cache", "catalogue 42", "region cache"), calls);
        assertEquals(1, scheduled.size(), "one refresh for the burst");

        scheduled.get(0).run();
        assertEquals(List.of("model", "messages"), calls.subList(4, 6));

        router.route("Landmark", null, null, "Updated");
        assertTrue(calls.contains("catalogue all"), "no id: the whole catalogue");
    }

    @Test
    void missingCachesAreSkipped_AndTheNpcRewarmHearsLandmarksToo() {
        LandmarkRealtime.register(router, coalescer, null, null, null, null, null);
        assertTrue(!router.route("Landmark", 41, null, "Updated"), "nothing registered");
        assertTrue(NavigationDomainChanges.DOMAIN_TYPES.contains("Landmark"));
    }
}
