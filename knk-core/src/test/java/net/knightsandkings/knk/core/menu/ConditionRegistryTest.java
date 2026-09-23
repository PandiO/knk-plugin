package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionRegistryTest {

    @Test
    void testReturnsTheHandlersOutcome() {
        ConditionRegistry<String> registry = new ConditionRegistry<>();
        registry.register("always", (context, params) -> ConditionOutcome.allow());
        registry.register("never", (context, params) -> ConditionOutcome.deny("nope"));

        assertTrue(registry.test("always", "ctx", Map.of()).allowed());
        ConditionOutcome denied = registry.test("never", "ctx", Map.of());
        assertFalse(denied.allowed());
        assertEquals("nope", denied.denialMessage());
    }

    @Test
    void aSilentDenialCarriesNoMessage() {
        ConditionOutcome outcome = ConditionOutcome.deny();
        assertFalse(outcome.allowed());
        assertNull(outcome.denialMessage());
    }

    @Test
    void testingAnUnregisteredIdFailsLoudlyRatherThanSilentlyNoOp() {
        ConditionRegistry<String> registry = new ConditionRegistry<>();

        MenuActionException ex = assertThrows(MenuActionException.class,
                () -> registry.test("does.not.exist", "ctx", Map.of()));

        assertTrue(ex.getMessage().contains("does.not.exist"));
    }

    @Test
    void isRegisteredReflectsRegistrationState() {
        ConditionRegistry<String> registry = new ConditionRegistry<>();
        assertFalse(registry.isRegistered("always"));

        registry.register("always", (context, params) -> ConditionOutcome.allow());

        assertTrue(registry.isRegistered("always"));
        assertEquals(Set.of("always"), registry.registeredIds());
    }
}
