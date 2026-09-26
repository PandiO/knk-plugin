package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.knightsandkings.knk.core.menu.Phase9Fixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InventoryMenu Phase 9: resolver changes - E8 lore omission/expansion, E1 ctx
 * hops, E3 row-scoped caching and params interpolation.
 */
class VariableResolverPhase9Test {

    private final MenuSession session = new MenuSession(UUID.randomUUID());

    private static final Phase9Fixtures.LobbyRow ROW = new Phase9Fixtures.LobbyRow(
            3, "Lobby Three", List.of("&aClick to view and join!", "&7second"), null);

    private Map<String, Object> scope() {
        return MenuVariableScope.of(Map.of(
                "row", ROW,
                "ctx", MenuContextParams.of(Map.of("lobbyId", "3")),
                "player", new Phase9Fixtures.FakePlayer("Steve", true)));
    }

    @Test
    void wholeExpressionNullDropsTheLoreLine() {
        List<KnkVariableBinding> bindings = List.of(
                binding(1, "Lore", 0, "&7before", "Static", null),
                binding(2, "Lore", 1, "$row.getNote$", "OnDirty", null),
                binding(3, "Lore", 2, "&7after", "Static", null));

        assertEquals(List.of("&7before", "&7after"), VariableResolver.resolveLore(bindings, session, scope(), 0L));
    }

    @Test
    void wholeExpressionListExpandsIntoSeveralLinesInOrder() {
        List<KnkVariableBinding> bindings = List.of(
                binding(1, "Lore", 0, "first", "Static", null),
                binding(2, "Lore", 1, "$row.getHints$", "OnDirty", null),
                binding(3, "Lore", 2, "last", "Static", null));

        assertEquals(List.of("first", "&aClick to view and join!", "&7second", "last"),
                VariableResolver.resolveLore(bindings, session, scope(), 0L));
    }

    @Test
    void emptyStringStaysABlankLine() {
        List<KnkVariableBinding> bindings = List.of(
                binding(1, "Lore", 0, "a", "Static", null),
                binding(2, "Lore", 1, "", "Static", null),
                binding(3, "Lore", 2, "b", "Static", null));

        assertEquals(List.of("a", "", "b"), VariableResolver.resolveLore(bindings, session, scope(), 0L));
    }

    @Test
    void mixedTextRendersNullAsEmptyAndJoinsLists() {
        List<KnkVariableBinding> bindings = List.of(
                binding(1, "Lore", 0, "&7Note: $row.getNote$!", "OnDirty", null),
                binding(2, "Lore", 1, "&7Hints: $row.getHints$", "OnDirty", null));

        assertEquals(List.of("&7Note: !", "&7Hints: &aClick to view and join!, &7second"),
                VariableResolver.resolveLore(bindings, session, scope(), 0L));
    }

    @Test
    void nullNameLeavesTheNameUnset() {
        assertNull(VariableResolver.resolveName(List.of(binding(1, "Name", "$row.getNote$")), session, scope(), 0L));
    }

    @Test
    void arraysAndOptionalsFollowTheSameShapeRules() {
        Map<String, Object> values = MenuVariableScope.of(Map.of("v", new ArrayHolder()));
        assertEquals(List.of("x", "y"), VariableResolver.resolveLore(
                List.of(binding(1, "Lore", "$v.getLines$")), session, values, 0L));
        assertEquals(List.of(), VariableResolver.resolveLore(
                List.of(binding(2, "Lore", "$v.getNothing$")), session, values, 0L));
        assertEquals(List.of("present"), VariableResolver.resolveLore(
                List.of(binding(3, "Lore", "$v.getSomething$")), session, values, 0L));
    }

    @Test
    void ctxHopIsAKeyLookupAndAMissingKeyIsEmpty() {
        assertEquals("Lobby 3", VariableResolver.resolve(binding(1, "Name", "Lobby $ctx.lobbyId$"), session, scope(), 0L));
        assertEquals("Lobby ", VariableResolver.resolve(binding(2, "Name", "Lobby $ctx.missing$"), session, scope(), 0L));
    }

    @Test
    void interpolateResolvesParamsValuesUncached() {
        Map<String, String> params = MenuParams.resolve(
                "{\"key\":\"siege.information\",\"ctx.lobbyId\":\"$row.getLobbyId$\",\"lobbyId\":\"$ctx.lobbyId$\"}", scope());

        assertEquals("siege.information", params.get("key"));
        assertEquals("3", params.get("ctx.lobbyId"));
        assertEquals("3", params.get("lobbyId"));
    }

    @Test
    void rowScopedBindingsNeverShareACacheEntryAcrossRows() {
        KnkVariableBinding name = binding(7, "Name", 0, "$row.getName$", "Static", null);
        Phase9Fixtures.LobbyRow first = new Phase9Fixtures.LobbyRow(1, "One", List.of(), null);
        Phase9Fixtures.LobbyRow second = new Phase9Fixtures.LobbyRow(2, "Two", List.of(), null);

        String a = VariableResolver.resolveName(List.of(name), session, MenuVariableScope.of(Map.of("row", first)), 0L,
                VariableResolver.RowScope.forRow(10, 0, first));
        String b = VariableResolver.resolveName(List.of(name), session, MenuVariableScope.of(Map.of("row", second)), 0L,
                VariableResolver.RowScope.forRow(10, 1, second));

        assertEquals("One", a);
        assertEquals("Two", b, "same binding id, different row position - separate cache entries");
    }

    @Test
    void aDifferentRowAtTheSamePositionIsACacheMissEvenForStatic() {
        KnkVariableBinding name = binding(7, "Name", 0, "$row.getName$", "Static", null);
        Phase9Fixtures.LobbyRow pageOne = new Phase9Fixtures.LobbyRow(1, "One", List.of(), null);
        Phase9Fixtures.LobbyRow pageTwo = new Phase9Fixtures.LobbyRow(9, "Nine", List.of(), null);

        VariableResolver.resolveName(List.of(name), session, MenuVariableScope.of(Map.of("row", pageOne)), 0L,
                VariableResolver.RowScope.forRow(10, 0, pageOne));
        String afterPaging = VariableResolver.resolveName(List.of(name), session, MenuVariableScope.of(Map.of("row", pageTwo)), 1L,
                VariableResolver.RowScope.forRow(10, 0, pageTwo));

        assertEquals("Nine", afterPaging);
        assertEquals(1, session.cachedVariableCount(), "bounded by position, not by rows seen");
    }

    @Test
    void menuRowKeyKeepsStaticRowBindingsCachedWhileTheSameLogicalRowStays() {
        KnkVariableBinding label = binding(8, "Name", 0, "$row.getLabel$", "Static", null);
        Phase9Fixtures.KeyedRow before = new Phase9Fixtures.KeyedRow(5, "old label");
        Phase9Fixtures.KeyedRow after = new Phase9Fixtures.KeyedRow(5, "new label");

        VariableResolver.resolveName(List.of(label), session, MenuVariableScope.of(Map.of("row", before)), 0L,
                VariableResolver.RowScope.forRow(10, 0, before));
        String cached = VariableResolver.resolveName(List.of(label), session, MenuVariableScope.of(Map.of("row", after)), 5L,
                VariableResolver.RowScope.forRow(10, 0, after));

        assertEquals("old label", cached, "same menuRowKey at the same position: Static stays cached");
    }

    @Test
    void cachedListsHonourTheRefreshPolicyToo() {
        MutableLines holder = new MutableLines();
        Map<String, Object> values = MenuVariableScope.of(Map.of("v", holder));
        KnkVariableBinding ttl = binding(9, "Lore", 0, "$v.getLines$", "Ttl", 20);

        assertEquals(List.of("a"), VariableResolver.resolveLore(List.of(ttl), session, values, 0L));
        holder.lines = List.of("b", "c");
        assertEquals(List.of("a"), VariableResolver.resolveLore(List.of(ttl), session, values, 10L), "within TTL");
        assertEquals(List.of("b", "c"), VariableResolver.resolveLore(List.of(ttl), session, values, 25L), "TTL elapsed");
    }

    @Test
    void publicGettersOnNonPublicClassesStillResolve() {
        Map<String, Object> values = MenuVariableScope.of(Map.of("hidden", new PrivateView()));
        assertEquals("visible", VariableResolver.resolve(binding(1, "Name", "$hidden.getValue$"), session, values, 0L));
    }

    @Test
    void resolveByTargetPropertyIsEmptyWhenTheBindingResolvesToNull() {
        Optional<String> material = VariableResolver.resolveByTargetProperty(
                List.of(binding(1, "Material", "$row.getNote$")), "Material", session, scope(), 0L);
        assertTrue(material.isEmpty());
    }

    public static final class ArrayHolder {
        public String[] getLines() {
            return new String[]{"x", null, "y"};
        }

        public Optional<String> getNothing() {
            return Optional.empty();
        }

        public Optional<String> getSomething() {
            return Optional.of("present");
        }
    }

    public static final class MutableLines {
        List<String> lines = Arrays.asList("a");

        public List<String> getLines() {
            return lines;
        }
    }

    private static final class PrivateView {
        public String getValue() {
            return "visible";
        }
    }
}
