package net.knightsandkings.knk.paper.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@code /knk cache refresh} (KNG-104): the hooks run in the order they were registered. */
class CacheManagerRefreshHooksTest {

    @Test
    void hooksRunInRegistrationOrder() {
        // the region → domain map is registered first, so navigation re-checks its routes against fresh domains
        CacheManager manager = new CacheManager(Duration.ofMinutes(1));
        List<String> ran = new ArrayList<>();
        for (String name : List.of("region domains", "navigation destinations", "navigation routes", "permissions",
            "spawn destination", "a", "z")) {
            manager.registerRefreshHook(name, () -> ran.add(name));
        }

        manager.runRefreshHooks();

        assertEquals(List.of("region domains", "navigation destinations", "navigation routes", "permissions",
            "spawn destination", "a", "z"), ran);
    }
}
