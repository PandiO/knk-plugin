package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InventoryMenu Phase 9: E2 (feature-registered variable roots, registration
 * before validation) and E3 (row sources declaring their row type).
 */
class MenuRegistriesPhase9Test {

    // ---- E2: MenuVariableProviderRegistry ----

    @Test
    void declaredTypesIncludeRegisteredRootsAndEngineRoots() {
        MenuVariableProviderRegistry<String> registry = new MenuVariableProviderRegistry<>();
        registry.register("player", Phase9Fixtures.FakePlayer.class, (player, ctx) -> new Phase9Fixtures.FakePlayer(player, false));

        Map<String, Class<?>> types = registry.declaredTypes();

        assertEquals(Phase9Fixtures.FakePlayer.class, types.get("player"));
        assertEquals(MenuContextParams.class, types.get("ctx"));
        assertEquals(MenuView.class, types.get("menu"));
        assertEquals(SectionView.class, types.get("section"));
        assertFalse(types.containsKey("row"), "row's type is per content source, not global");
    }

    @Test
    void engineRootsAreReserved() {
        MenuVariableProviderRegistry<String> registry = new MenuVariableProviderRegistry<>();
        for (String reserved : List.of("ctx", "menu", "section", "row")) {
            assertThrows(IllegalArgumentException.class, () -> registry.register(reserved, Object.class, (p, c) -> null));
        }
        assertThrows(IllegalArgumentException.class, () -> registry.register("not.a.root", Object.class, (p, c) -> null));
    }

    @Test
    void registeringAfterValidationLockedTheRegistryFailsLoudly() {
        MenuVariableProviderRegistry<String> variables = new MenuVariableProviderRegistry<>();
        ActionRegistry<String> actions = new ActionRegistry<>();
        ConditionRegistry<String> conditions = new ConditionRegistry<>();
        MenuContentSourceRegistry<String> sources = new MenuContentSourceRegistry<>();
        variables.lock();
        actions.lock();
        conditions.lock();
        sources.lock();

        assertThrows(IllegalStateException.class, () -> variables.register("siege", Object.class, (p, c) -> null));
        assertThrows(IllegalStateException.class, () -> actions.register("siege.join", (c, p) -> { }));
        assertThrows(IllegalStateException.class, () -> conditions.register("siege.phase", (c, p) -> ConditionOutcome.allow()));
        assertThrows(IllegalStateException.class, () -> sources.registerRows("siege.lobbies", Object.class,
                (c, p, q) -> CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 1))));
    }

    @Test
    void providersAreLazyMemoisedAndReceivePlayerAndContext() {
        MenuVariableProviderRegistry<String> registry = new MenuVariableProviderRegistry<>();
        AtomicInteger siegeCalls = new AtomicInteger();
        AtomicInteger unusedCalls = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>();
        registry.register("siege", String.class, (player, ctx) -> {
            siegeCalls.incrementAndGet();
            seen.set(player + "@" + ctx.get("lobbyId"));
            return "lobby " + ctx.get("lobbyId");
        });
        registry.register("unused", String.class, (player, ctx) -> {
            unusedCalls.incrementAndGet();
            return "never";
        });

        MenuVariableScope scope = registry.scope("Steve", MenuContextParams.of(Map.of("lobbyId", "3")), Map.of());
        assertEquals("lobby 3", scope.get("siege"));
        assertEquals("lobby 3", scope.get("siege"));
        MenuVariableScope rowScope = scope.with("row", "a row");
        assertEquals("lobby 3", rowScope.get("siege"), "child scopes share the parent's memo");
        assertEquals("a row", rowScope.get("row"));

        assertEquals(1, siegeCalls.get());
        assertEquals(0, unusedCalls.get(), "a root no binding reads is never provided");
        assertEquals("Steve@3", seen.get());
        assertEquals("3", ((MenuContextParams) scope.get("ctx")).get("lobbyId"));
        assertTrue(scope.containsKey("unused"));
        assertFalse(scope.containsKey("row"));
    }

    @Test
    void aNullProviderResultIsMemoisedAndResolvesAsAbsent() {
        MenuVariableProviderRegistry<String> registry = new MenuVariableProviderRegistry<>();
        AtomicInteger calls = new AtomicInteger();
        registry.register("siegeViewer", Phase9Fixtures.LobbyRow.class, (player, ctx) -> {
            calls.incrementAndGet();
            return null;
        });
        MenuVariableScope scope = registry.scope("Steve", MenuContextParams.EMPTY, Map.of());

        MenuSession session = new MenuSession(java.util.UUID.randomUUID());
        List<String> lore = VariableResolver.resolveLore(List.of(
                Phase9Fixtures.binding(1, "Lore", 0, "$siegeViewer.getNote$", "OnDirty", null),
                Phase9Fixtures.binding(2, "Lore", 1, "$siegeViewer.getName$", "OnDirty", null)), session, scope, 0L);

        assertTrue(lore.isEmpty());
        assertEquals(1, calls.get());
    }

    // ---- E3: row sources ----

    @Test
    void rowSourcesDeclareTheirRowTypeAndItemSourcesStayUnchanged() {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        registry.register("catalog.itemblueprints", (context, query) ->
                CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 1)));
        registry.registerRows("siege.lobbies", Phase9Fixtures.LobbyRow.class,
                (context, params, query) -> CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 1)));

        assertEquals(Map.of("siege.lobbies", Phase9Fixtures.LobbyRow.class), registry.rowTypes());
        assertNull(registry.rowType("catalog.itemblueprints"));
        assertTrue(registry.registeredIds().containsAll(List.of("catalog.itemblueprints", "siege.lobbies")));
        assertFalse(registry.fetch("catalog.itemblueprints", "ctx", Map.of(), new PagedQuery(1, 5, null, null, false, Map.of()))
                .join().rows());
    }

    @Test
    void rowSourcesGetInterpolatedParamsAndUnpagedResultsAreSlicedToThePage() {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        AtomicReference<Map<String, String>> receivedParams = new AtomicReference<>();
        registry.registerRows("example.rows", Integer.class, (context, params, query) -> {
            receivedParams.set(params);
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                all.add(i);
            }
            return CompletableFuture.completedFuture(new Page<>(all, all.size(), 1, all.size()));
        });

        MenuContentSourceRegistry.MenuContentPage page = registry.fetch("example.rows", "ctx", Map.of("lobbyId", "3"),
                new PagedQuery(2, 4, null, null, false, Map.of())).join();

        assertTrue(page.rows());
        assertEquals(List.of(5, 6, 7, 8), page.page().items());
        assertEquals(10, page.page().totalCount());
        assertEquals(Map.of("lobbyId", "3"), receivedParams.get());
    }

    @Test
    void aThrowingRowSourceBecomesAFailedFuture() {
        MenuContentSourceRegistry<String> registry = new MenuContentSourceRegistry<>();
        registry.registerRows("broken", Integer.class, (context, params, query) -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(registry.fetch("broken", "ctx", Map.of(), new PagedQuery(1, 4, null, null, false, Map.of()))
                .isCompletedExceptionally());
    }
}
