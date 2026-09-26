package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Content port CP6 (engine gap G1): per-session menu state and the {@code state} engine root. */
class MenuStateG1Test {

    private final MenuSession session = new MenuSession(UUID.randomUUID());

    @Test
    void setUnsetAndSetIfAbsent() {
        session.setState("pm.coinStep", "100");
        assertEquals("100", session.getState("pm.coinStep"));
        assertFalse(session.setStateIfAbsent("pm.coinStep", "1"), "a default never overwrites");
        assertTrue(session.setStateIfAbsent("pm.gemStep", "10"));
        assertEquals(Map.of("pm.coinStep", "100", "pm.gemStep", "10"), session.stateSnapshot());

        session.setState("pm.coinStep", null);
        assertNull(session.getState("pm.coinStep"));
        assertThrows(IllegalArgumentException.class, () -> session.setState(" ", "x"));
    }

    @Test
    void cycleStartsAtTheFirstValueAdvancesAndWraps() {
        List<String> steps = List.of("1", "10", "100");

        assertEquals("1", session.cycleState("s", steps), "unset -> first");
        assertEquals("10", session.cycleState("s", steps));
        assertEquals("100", session.cycleState("s", steps));
        assertEquals("1", session.cycleState("s", steps), "wraps");
        session.setState("s", "999");
        assertEquals("1", session.cycleState("s", steps), "value not in the list -> first");
        assertThrows(IllegalArgumentException.class, () -> session.cycleState("s", List.of()));
    }

    @Test
    void aFreshNavigationRootClearsStateButNavigatingKeepsIt() {
        session.openAsRoot("main", MenuContextParams.EMPTY, "Main");
        session.setState("pm.coinStep", "1000");
        session.navigateTo("users.manager.edit", MenuContextParams.of(Map.of("userId", "4")), "Edit");
        session.goBackEntry();
        assertEquals("1000", session.getState("pm.coinStep"));

        session.openAsRoot("main", MenuContextParams.EMPTY, "Main");
        assertTrue(session.stateSnapshot().isEmpty());
    }

    @Test
    void closingTheSessionDropsItsState() {
        MenuSessionRegistry registry = new MenuSessionRegistry();
        UUID player = UUID.randomUUID();
        registry.open(player).setState("pm.coinStep", "5");

        registry.close(player);

        assertNull(registry.open(player).getState("pm.coinStep"));
    }

    @Test
    void stateRootResolvesDottedKeysAndEmptyWhenUnset() {
        session.setState("pm.coinStep", "100");
        Map<String, Object> scope = Map.of(MenuVariableProviderRegistry.ROOT_STATE, MenuStateView.of(session));

        assertEquals("100", VariableResolver.interpolate("$state.pm.coinStep$", scope));
        assertEquals("-100", VariableResolver.interpolate("-$state.pm.coinStep$", scope), "negated step for a '-' button");
        assertEquals("Step: ", VariableResolver.interpolate("Step: $state.pm.gemStep$", scope));
        assertEquals("", MenuStateView.of(null).get("x"));
    }

    @Test
    void stateViewIsLiveSoARenderAfterASetSeesTheNewValue() {
        MenuStateView view = MenuStateView.of(session);
        session.setState("k", "a");
        assertEquals("a", view.get("k"));
        session.setState("k", "b");
        assertEquals("b", view.get("k"));
    }

    @Test
    void stateIsAReservedEngineRootAndIsAlwaysDeclared() {
        MenuVariableProviderRegistry<String> registry = new MenuVariableProviderRegistry<>();
        assertThrows(IllegalArgumentException.class, () -> registry.register("state", String.class, (p, c) -> "x"));
        assertEquals(MenuStateView.class, registry.declaredTypes().get("state"));
    }

    @Test
    void validatorAcceptsStateChainsAnywhereAndStillRejectsUnknownRoots() {
        RuntimeMenuItem ok = Phase9Fixtures.item(1, 0, List.of(
                Phase9Fixtures.binding(10, "Name", 0, "&7Step: &f$state.pm.coinStep$", "OnDirty", null),
                Phase9Fixtures.binding(11, "Lore", 0, "$state.x$ / $state$", "OnDirty", null)));
        RuntimeMenu menu = Phase9Fixtures.menu("t", Phase9Fixtures.section(1, "S", 0, 9, 1, List.of(ok), null, Map.of()));
        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, Map.of()));

        RuntimeMenuItem bad = Phase9Fixtures.item(2, 1, List.of(
                Phase9Fixtures.binding(12, "Name", 0, "$states.pm$", "OnDirty", null)));
        RuntimeMenu broken = Phase9Fixtures.menu("t", Phase9Fixtures.section(1, "S", 0, 9, 1, List.of(bad), null, Map.of()));
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(broken, Map.of()));
    }
}
