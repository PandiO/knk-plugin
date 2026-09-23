package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableResolverTest {

    /**
     * Reconciliation bug #7: v2's {@code $(.*?)$} regex never actually
     * substituted {@code %...%} variables, and nobody noticed for the
     * entirety of v2's lifetime. This is the regression test
     * RECONCILIATION.md and IMPLEMENTATION_PLAN.md both require before this
     * phase can ship: real end-to-end substitution against a live-looking
     * getter chain, not just an architecture read-through.
     */
    @Test
    void regressionBug7_realSubstitutionHappensEndToEnd() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        FakePlayer player = new FakePlayer("Steve");
        Map<String, Object> context = Map.of("player", player);

        KnkVariableBinding binding = new KnkVariableBinding(1, "Name", 0, "Player: $player.getName$", "OnDirty", null);

        String resolved = VariableResolver.resolve(binding, session, context, 0L);

        assertEquals("Player: Steve", resolved);
        assertTrue(!resolved.contains("$"), "resolved text must not still contain the raw placeholder syntax");
    }

    @Test
    void literalExpressionWithNoPlaceholderPassesThroughUnchanged() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        KnkVariableBinding binding = new KnkVariableBinding(2, "Lore", 0, "Click to close", "Static", null);

        assertEquals("Click to close", VariableResolver.resolve(binding, session, Map.of(), 0L));
    }

    @Test
    void staticPolicyCachesForeverEvenAfterTheUnderlyingValueChanges() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        FakePlayer player = new FakePlayer("Alice");
        Map<String, Object> context = Map.of("player", player);
        KnkVariableBinding binding = new KnkVariableBinding(3, "Name", 0, "$player.getName$", "Static", null);

        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 0L));

        player.setName("Bob");
        session.markDirty(); // STATIC must ignore this entirely
        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 1000L));
    }

    @Test
    void onDirtyPolicyOnlyRefreshesAfterMarkDirty() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        FakePlayer player = new FakePlayer("Alice");
        Map<String, Object> context = Map.of("player", player);
        KnkVariableBinding binding = new KnkVariableBinding(4, "Name", 0, "$player.getName$", "OnDirty", null);

        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 0L));

        player.setName("Bob");
        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 1L), "unchanged: not marked dirty yet");

        session.markDirty();
        assertEquals("Bob", VariableResolver.resolve(binding, session, context, 2L), "re-resolves once dirty");

        session.clearDirty();
        player.setName("Carol");
        assertEquals("Bob", VariableResolver.resolve(binding, session, context, 3L), "cached again after dirty is cleared");
    }

    @Test
    void ttlPolicyRefreshesOnlyAfterElapsedTicks() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        FakePlayer player = new FakePlayer("Alice");
        Map<String, Object> context = Map.of("player", player);
        KnkVariableBinding binding = new KnkVariableBinding(5, "Name", 0, "$player.getName$", "Ttl", 10);

        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 0L));

        player.setName("Bob");
        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 5L), "within TTL window");
        assertEquals("Bob", VariableResolver.resolve(binding, session, context, 11L), "TTL elapsed");
    }

    @Test
    void ttlPolicyFallsBackToADefaultWhenTtlTicksIsNull() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        FakePlayer player = new FakePlayer("Alice");
        Map<String, Object> context = Map.of("player", player);
        KnkVariableBinding binding = new KnkVariableBinding(6, "Name", 0, "$player.getName$", "Ttl", null);

        assertEquals("Alice", VariableResolver.resolve(binding, session, context, 0L));
        player.setName("Bob");
        assertEquals("Bob", VariableResolver.resolve(binding, session, context, 1000L));
    }

    @Test
    void unknownRootVariableResolvesToEmptyStringRatherThanCrashing() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        KnkVariableBinding binding = new KnkVariableBinding(7, "Name", 0, "$nonexistent.getX$", "Static", null);

        assertEquals("", VariableResolver.resolve(binding, session, Map.of("player", new FakePlayer("Alice")), 0L));
    }

    @Test
    void resolveNameAndResolveLoreMirrorTheOldPlaceholderHelperShape() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        Map<String, Object> context = Map.of("player", new FakePlayer("Steve"));

        List<KnkVariableBinding> bindings = List.of(
                new KnkVariableBinding(8, "Lore", 1, "second line", "Static", null),
                new KnkVariableBinding(9, "Lore", 0, "first line", "Static", null),
                new KnkVariableBinding(10, "Name", 0, "$player.getName$", "Static", null)
        );

        assertEquals("Steve", VariableResolver.resolveName(bindings, session, context, 0L));
        assertEquals(List.of("first line", "second line"), VariableResolver.resolveLore(bindings, session, context, 0L));

        assertNull(VariableResolver.resolveName(List.of(), session, context, 0L));
        assertTrue(VariableResolver.resolveLore(List.of(), session, context, 0L).isEmpty());
    }

    /** A tiny local stand-in for {@code org.bukkit.entity.Player} so this test stays Bukkit-free. */
    private static final class FakePlayer {
        private String name;

        FakePlayer(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }
}
