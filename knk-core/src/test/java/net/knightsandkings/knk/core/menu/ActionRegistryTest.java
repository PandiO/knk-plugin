package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IMPLEMENTATION_PLAN.md Phase 6 "Code-side registries": the mechanism
 * itself - lookup, registration, and the "unregistered id fails loudly"
 * policy - is exercised here with a trivial fake context type, proving the
 * registry doesn't actually need Bukkit to do its job.
 */
class ActionRegistryTest {

    @Test
    void executeInvokesTheRegisteredHandlerWithContextAndParams() {
        ActionRegistry<String> registry = new ActionRegistry<>();
        AtomicReference<String> seenContext = new AtomicReference<>();
        AtomicReference<Map<String, String>> seenParams = new AtomicReference<>();
        registry.register("test.action", (context, params) -> {
            seenContext.set(context);
            seenParams.set(params);
        });

        registry.execute("test.action", "the-context", Map.of("key", "value"));

        assertEquals("the-context", seenContext.get());
        assertEquals(Map.of("key", "value"), seenParams.get());
    }

    @Test
    void executingAnUnregisteredIdFailsLoudlyRatherThanSilentlyNoOp() {
        ActionRegistry<String> registry = new ActionRegistry<>();

        MenuActionException ex = assertThrows(MenuActionException.class,
                () -> registry.execute("does.not.exist", "context", Map.of()));

        assertTrue(ex.getMessage().contains("does.not.exist"));
    }

    @Test
    void isRegisteredAndRegisteredIdsReflectWhatWasRegistered() {
        ActionRegistry<String> registry = new ActionRegistry<>();
        assertFalse(registry.isRegistered("a"));

        registry.register("a", (context, params) -> {
        });
        registry.register("b", (context, params) -> {
        });

        assertTrue(registry.isRegistered("a"));
        assertTrue(registry.isRegistered("b"));
        assertFalse(registry.isRegistered("c"));
        assertEquals(List.of("a", "b"), registry.registeredIds().stream().sorted().toList());
    }
}
