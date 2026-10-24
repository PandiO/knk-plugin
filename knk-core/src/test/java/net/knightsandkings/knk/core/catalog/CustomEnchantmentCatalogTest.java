package net.knightsandkings.knk.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentType;

/** KNG-250: the custom enchantments of the snapshot follow IMPLEMENTATION_PLAN.md §12.2. */
class CustomEnchantmentCatalogTest {

    @Test
    void everyRegistryEnchantmentIsACustomEntry() {
        List<CatalogEnchantment> entries = CustomEnchantmentCatalog.entries();

        assertEquals(13, entries.size());
        assertEquals(13, entries.stream().map(CatalogEnchantment::key).distinct().count());
        assertEquals(new CatalogEnchantment("knk:flash_chaos", true, 1, null, "Flash Chaos", "SUPPORT"),
            entries.stream().filter(e -> e.key().equals("knk:flash_chaos")).findFirst().orElseThrow());
        assertEquals(new CatalogEnchantment("knk:poison", true, 3, null, "Poison", "ATTACK"),
            entries.stream().filter(e -> e.key().equals("knk:poison")).findFirst().orElseThrow());
        for (CatalogEnchantment entry : entries) {
            assertTrue(entry.isCustom(), entry.key());
            assertTrue(entry.key().matches("knk:[a-z0-9_]+"), entry.key());
            assertEquals(null, entry.translationKey(), entry.key());
            assertTrue(entry.maxLevel() != null && entry.maxLevel() >= 1, entry.key());
        }
        assertEquals(Set.of("ATTACK", "SUPPORT"), entries.stream().map(CatalogEnchantment::category).collect(Collectors.toSet()));
        assertEquals(7, entries.stream().filter(e -> "ATTACK".equals(e.category())).count());
    }

    @Test
    void theKeyIsLowerCased() {
        CatalogEnchantment entry = CustomEnchantmentCatalog.entryOf(
            new Enchantment("Health_Boost", "Health Boost", 1, 0, EnchantmentType.SUPPORT, null));

        assertEquals("knk:health_boost", entry.key());
    }

    @Test
    void merge_keepsRegistryEntriesFirst_andSkipsACollidingCustomOne() {
        CatalogEnchantment sharpness = new CatalogEnchantment("minecraft:sharpness", false, 5, "enchantment.minecraft.sharpness", "Sharpness", null);
        CatalogEnchantment dataPack = new CatalogEnchantment("knk:chaos", false, 2, "enchantment.knk.chaos", null, null);
        CatalogEnchantment customChaos = new CatalogEnchantment("knk:chaos", true, 1, null, "Chaos", "SUPPORT");
        CatalogEnchantment customPoison = new CatalogEnchantment("knk:poison", true, 3, null, "Poison", "ATTACK");
        List<String> warnings = new ArrayList<>();

        List<CatalogEnchantment> merged = CustomEnchantmentCatalog.merge(
            List.of(sharpness, dataPack), List.of(customChaos, customPoison, customPoison), warnings::add);

        assertEquals(List.of(sharpness, dataPack, customPoison), merged);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("knk:chaos"), warnings.get(0));
    }
}
