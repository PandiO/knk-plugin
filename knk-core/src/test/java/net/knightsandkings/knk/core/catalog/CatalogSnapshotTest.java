package net.knightsandkings.knk.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * KNG-200/KNG-250: the content hash follows IMPLEMENTATION_PLAN.md §5.2a (material text) and §12.2
 * (schema version 2), pinned by the test vectors that knk-web-api pins too.
 */
class CatalogSnapshotTest {

    private static final CatalogEntry STONE = new CatalogEntry("minecraft:stone", true, true, 64,
        "block.minecraft.stone", "Stone", "minecraft:stone");
    private static final CatalogEntry APPLE = new CatalogEntry("minecraft:apple", true, false, 64,
        "item.minecraft.apple", null, null);
    private static final CatalogEnchantment SHARPNESS = new CatalogEnchantment("minecraft:sharpness", false, 5,
        "enchantment.minecraft.sharpness", "Sharpness", null);
    private static final CatalogEnchantment FLASH_CHAOS = new CatalogEnchantment("knk:flash_chaos", true, 1,
        null, "Flash Chaos", "SUPPORT");

    private static final String MATERIAL_TEXT = "minecraft:apple|true|false|64|item.minecraft.apple||\n"
        + "minecraft:stone|true|true|64|block.minecraft.stone|Stone|minecraft:stone";

    @Test
    void materialText_isTheSchema1Text() {
        assertEquals(MATERIAL_TEXT, CatalogSnapshot.materialText(List.of(STONE, APPLE)));
    }

    @Test
    void contractTestVectorV2_withEnchantments() {
        List<CatalogEntry> entries = List.of(STONE, APPLE);
        List<CatalogEnchantment> enchantments = List.of(SHARPNESS, FLASH_CHAOS);

        assertEquals(MATERIAL_TEXT + "\n#enchantments\n"
                + "knk:flash_chaos|true|1||Flash Chaos|SUPPORT\n"
                + "minecraft:sharpness|false|5|enchantment.minecraft.sharpness|Sharpness|",
            CatalogSnapshot.canonicalText(entries, enchantments));
        assertEquals("ab2c65626e6508eb4c169dabefda5ef7570f94c8d966f31adecc624dfe823154",
            CatalogSnapshot.contentHash(entries, enchantments));
    }

    @Test
    void contractTestVectorV2_withoutEnchantments() {
        List<CatalogEntry> entries = List.of(STONE, APPLE);

        assertEquals(MATERIAL_TEXT + "\n#enchantments", CatalogSnapshot.canonicalText(entries, List.of()));
        assertEquals("9846ad62d84d42d5a05e0cbac900ab6f671bf3bb0dd7c1bb5961aa2316f63c3e",
            CatalogSnapshot.contentHash(entries, List.of()));
    }

    @Test
    void of_sortsEntriesAndEnchantments_andCarriesTheV2Hash() {
        CatalogSnapshot snapshot = CatalogSnapshot.of("26.3", "paper", List.of(STONE, APPLE), List.of(SHARPNESS, FLASH_CHAOS));

        assertEquals(2, snapshot.schemaVersion());
        assertEquals(List.of(APPLE, STONE), snapshot.entries());
        assertEquals(List.of(FLASH_CHAOS, SHARPNESS), snapshot.enchantments());
        assertEquals("ab2c65626e6508eb4c169dabefda5ef7570f94c8d966f31adecc624dfe823154", snapshot.contentHash());
    }

    @Test
    void theInputOrderDoesNotChangeTheHash() {
        List<CatalogEntry> entries = new ArrayList<>();
        List<CatalogEnchantment> enchantments = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new CatalogEntry("minecraft:block_" + i, i % 2 == 0, i % 3 == 0, i, "t" + i, null, "s" + i));
            enchantments.add(new CatalogEnchantment("minecraft:ench_" + i, i % 2 == 0, i % 4 == 0 ? null : i, "t" + i, null, null));
        }
        String expected = CatalogSnapshot.contentHash(entries, enchantments);

        for (long seed = 1; seed <= 5; seed++) {
            List<CatalogEntry> shuffledEntries = new ArrayList<>(entries);
            List<CatalogEnchantment> shuffledEnchantments = new ArrayList<>(enchantments);
            Collections.shuffle(shuffledEntries, new Random(seed));
            Collections.shuffle(shuffledEnchantments, new Random(seed + 100));
            assertEquals(expected, CatalogSnapshot.contentHash(shuffledEntries, shuffledEnchantments));
        }
    }

    @Test
    void keysSortOrdinally() {
        // Ordinal: '_' (0x5F) sorts after upper case and before lower case.
        CatalogEntry underscore = new CatalogEntry("minecraft:a_b", false, true, 1, null, null, null);
        CatalogEntry lower = new CatalogEntry("minecraft:ab", false, true, 1, null, null, null);
        assertEquals(List.of(underscore, lower), CatalogSnapshot.sortedByKey(List.of(lower, underscore)));

        CatalogEnchantment knk = new CatalogEnchantment("knk:z", true, 1, null, null, null);
        CatalogEnchantment minecraftUnderscore = new CatalogEnchantment("minecraft:a_b", false, 1, null, null, null);
        CatalogEnchantment minecraftLower = new CatalogEnchantment("minecraft:ab", false, 1, null, null, null);
        assertEquals(List.of(knk, minecraftUnderscore, minecraftLower),
            CatalogSnapshot.enchantmentsSortedByKey(List.of(minecraftLower, minecraftUnderscore, knk)));
    }

    @Test
    void nullFieldsAreEmpty_andDistinctFromTheLiteralNull() {
        CatalogEntry allNull = new CatalogEntry("minecraft:x", false, false, 1, null, null, null);
        CatalogEntry literalNull = new CatalogEntry("minecraft:x", false, false, 1, "null", null, null);
        assertEquals("minecraft:x|false|false|1|||", CatalogSnapshot.materialText(List.of(allNull)));
        assertNotEquals(CatalogSnapshot.contentHash(List.of(allNull), List.of()),
            CatalogSnapshot.contentHash(List.of(literalNull), List.of()));

        CatalogEnchantment enchantmentAllNull = new CatalogEnchantment("minecraft:y", false, null, null, null, null);
        CatalogEnchantment enchantmentLiteralNull = new CatalogEnchantment("minecraft:y", false, null, null, null, "null");
        assertEquals("minecraft:x|false|false|1|||\n#enchantments\nminecraft:y|false||||",
            CatalogSnapshot.canonicalText(List.of(allNull), List.of(enchantmentAllNull)));
        assertNotEquals(CatalogSnapshot.contentHash(List.of(allNull), List.of(enchantmentAllNull)),
            CatalogSnapshot.contentHash(List.of(allNull), List.of(enchantmentLiteralNull)));
    }

    @Test
    void anEmptyCatalogue_stillHasTheEnchantmentsMarker() {
        assertEquals("", CatalogSnapshot.materialText(List.of()));
        assertEquals("\n#enchantments", CatalogSnapshot.canonicalText(List.of(), List.of()));
    }
}
