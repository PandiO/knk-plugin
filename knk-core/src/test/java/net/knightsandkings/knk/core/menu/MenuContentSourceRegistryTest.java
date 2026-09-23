package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMPLEMENTATION_PLAN.md Phase 8: {@link MenuContentSourceRegistry} mirrors
 * {@link ActionRegistry}/{@link ConditionRegistry}'s established shape and
 * failure policy - these tests exercise that mirroring directly, the same
 * way {@code ActionRegistryTest}/{@code ConditionRegistryTest} do for those.
 */
class MenuContentSourceRegistryTest {

    private static final RuntimeMenuItem ITEM = new RuntimeMenuItem(
            1, 0, null, null, 1, null, null, MenuDisplayMode.NORMAL, null, null, List.of(), List.of(), List.of());

    @Test
    void unregisteredIdIsNotReportedAsRegistered() {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        assertFalse(registry.isRegistered("catalog.itemblueprints"));
        assertTrue(registry.registeredIds().isEmpty());
    }

    @Test
    void registeredIdIsReportedAndReachable() throws ExecutionException, InterruptedException {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        Page<RuntimeMenuItem> page = new Page<>(List.of(ITEM), 1, 1, 10);
        registry.register("catalog.itemblueprints", (context, query) -> CompletableFuture.completedFuture(page));

        assertTrue(registry.isRegistered("catalog.itemblueprints"));
        assertEquals(Set.of("catalog.itemblueprints"), registry.registeredIds());

        Page<RuntimeMenuItem> result = registry
                .fetchPage("catalog.itemblueprints", "ctx", new PagedQuery(1, 10, null, null, false, Map.of()))
                .get();
        assertEquals(1, result.items().size());
    }

    @Test
    void fetchingAnUnregisteredIdFailsLoudlyRatherThanSilentlyNoOp() {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        CompletableFuture<Page<RuntimeMenuItem>> future = registry.fetchPage(
                "catalog.itemblueprints", "ctx", new PagedQuery(1, 10, null, null, false, Map.of()));

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof MenuActionException);
        assertTrue(ex.getCause().getMessage().contains("catalog.itemblueprints"));
    }
}
