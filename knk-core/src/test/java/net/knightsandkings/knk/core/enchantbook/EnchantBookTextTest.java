package net.knightsandkings.knk.core.enchantbook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnchantBookTextTest {

    @Test
    void teaches_IsPrefixedSoTheCustomLoreParserIgnoresIt() {
        // The custom lore parser reads "<name> <roman>" where <name> is a whole custom display name;
        // "Teaches: Poison" is not one.
        assertEquals("Teaches: Poison II", EnchantBookText.teaches("Poison", 2));
    }

    @Test
    void roman_CoversOneToTenThenDigits() {
        assertEquals("I", EnchantBookText.roman(1));
        assertEquals("IV", EnchantBookText.roman(4));
        assertEquals("X", EnchantBookText.roman(10));
        assertEquals("11", EnchantBookText.roman(11));
        assertEquals("0", EnchantBookText.roman(0));
    }

    @Test
    void displayNameFromKey_TitleCasesTheBareKey() {
        assertEquals("Fire Aspect", EnchantBookText.displayNameFromKey("minecraft:fire_aspect"));
        assertEquals("Flash Chaos", EnchantBookText.displayNameFromKey("flash_chaos"));
        assertEquals("Unknown", EnchantBookText.displayNameFromKey(null));
    }
}
