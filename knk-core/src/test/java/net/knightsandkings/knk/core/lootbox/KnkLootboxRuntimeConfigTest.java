package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: the lookups /lootbox and /knk lootbox resolve names with. */
class KnkLootboxRuntimeConfigTest {

    private final KnkLootboxRuntimeConfig config = new KnkLootboxRuntimeConfig(true, 15, 10, 5, 6, null, null, null,
            List.of(new KnkLootboxType(3, "Weapons Lootbox", 1, "Weapons", "minecraft:chest", 10, 1, 5, null, null),
                    new KnkLootboxType(4, "Enchantment Books Lootbox", 7, "Enchantment Books", "minecraft:chest", 10, 1, 5, 2, null)),
            List.of(new KnkLootboxGrade(5, "Legendary", 5)),
            List.of(new KnkLootboxArea(9, "Spawn", "world", "lootbox_spawn", true, 3, 600, 100, 3, 24, 30, null, null, 0, null)));

    @Test
    void typeByCategory_matchesCategoryOrTypeName_caseAndUnderscoreInsensitive() {
        assertEquals(3, config.typeByCategory("weapons").orElseThrow().id());
        assertEquals(3, config.typeByCategory("Weapons Lootbox").orElseThrow().id());
        assertEquals(4, config.typeByCategory("enchantment_books").orElseThrow().id());
        assertTrue(config.typeByCategory("armor").isEmpty());
        assertTrue(config.typeByCategory(" ").isEmpty());
    }

    @Test
    void areaAndGradeLookups() {
        assertEquals(9, config.areaByName("spawn").orElseThrow().id());
        assertEquals("Legendary", config.gradeName(5).orElseThrow());
        assertTrue(config.gradeName(4).isEmpty());
        assertTrue(config.areaById(9).orElseThrow().excludedRegionIds().isEmpty());
    }

    @Test
    void empty_isDisabled() {
        assertEquals(false, KnkLootboxRuntimeConfig.empty().enabled());
        assertTrue(KnkLootboxRuntimeConfig.empty().types().isEmpty());
    }
}
