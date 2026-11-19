package net.knightsandkings.knk.paper.regions.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.ports.api.DomainAccessRulesApi;

/** Overview map F1 (PandiO/knk-workspace#750): the access sync hands every completed run's rules to its listener. */
class DomainAccessFlagSyncAfterSyncTest {

    private final DomainAccessFlagSync sync = new DomainAccessFlagSync(mock(JavaPlugin.class), mock(DomainAccessRulesApi.class),
        new DomainAccessFlagSync.Settings(true, 100, 3, 30, 5));

    private final List<DomainAccessRule> rules = List.of(new DomainAccessRule(1, "Town", "domain_1", true, true, "Town",
        "world", List.of("domain_1_sub_1")));

    @Test
    void theListenerGetsTheRules() {
        List<List<DomainAccessRule>> seen = new ArrayList<>();
        sync.afterSync(seen::add);

        sync.notifyAfterSync(rules);

        assertEquals(List.of(rules), seen);
    }

    @Test
    void noListener_nothingHappens() {
        assertDoesNotThrow(() -> sync.notifyAfterSync(rules));
    }

    @Test
    void aFailingListener_neverFailsTheSync() {
        sync.afterSync(r -> {
            throw new IllegalStateException("map down");
        });

        assertDoesNotThrow(() -> sync.notifyAfterSync(rules));
    }
}
