package net.knightsandkings.knk.core.domain.enchantment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantmentRegistryTest {

    @Test
    void allExpectedEnchantmentsAreRegistered() {
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();
        assertEquals(13, registry.getAll().size());
        assertTrue(registry.getById("poison").isPresent());
        assertTrue(registry.getById("flash_chaos").isPresent());
        assertTrue(registry.getById("invisibility").isPresent());
        assertTrue(registry.getById("cleaving").isPresent());
        assertEquals(4, registry.getById("cleaving").orElseThrow().maxLevel());
    }

    @Test
    void lookupByTypeReturnsExpectedCounts() {
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();
        assertEquals(7, registry.getByType(EnchantmentType.ATTACK).size());
        assertEquals(6, registry.getByType(EnchantmentType.SUPPORT).size());
    }

    @Test
    void levelValidationUsesMaxLevel() {
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();
        assertTrue(registry.existsAndValidLevel("poison", 3));
        assertFalse(registry.existsAndValidLevel("poison", 4));
        assertFalse(registry.existsAndValidLevel("unknown", 1));
        assertFalse(registry.existsAndValidLevel("strength", 0));
    }
}
