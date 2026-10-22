package net.knightsandkings.knk.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * KNG-200: the content hash follows IMPLEMENTATION_PLAN.md §5.2a, pinned by the test vector that
 * knk-web-api pins too.
 */
class CatalogSnapshotTest {

    private static final CatalogEntry STONE = new CatalogEntry("minecraft:stone", true, true, 64,
        "block.minecraft.stone", "Stone", "minecraft:stone");
    private static final CatalogEntry APPLE = new CatalogEntry("minecraft:apple", true, false, 64,
        "item.minecraft.apple", null, null);

    @Test
    void contractTestVector() {
        List<CatalogEntry> entries = List.of(STONE, APPLE);

        assertEquals("minecraft:apple|true|false|64|item.minecraft.apple||\n"
            + "minecraft:stone|true|true|64|block.minecraft.stone|Stone|minecraft:stone", CatalogSnapshot.canonicalText(entries));
        assertEquals("86979e0606b8c36d782bf7bc0100dc2d3559aa53d714900419f0850431d5fb12", CatalogSnapshot.contentHash(entries));
    }

    @Test
    void of_sortsTheEntriesAndCarriesTheHash() {
        CatalogSnapshot snapshot = CatalogSnapshot.of("1.21.10", "paper", List.of(STONE, APPLE));

        assertEquals(1, snapshot.schemaVersion());
        assertEquals(List.of(APPLE, STONE), snapshot.entries());
        assertEquals("86979e0606b8c36d782bf7bc0100dc2d3559aa53d714900419f0850431d5fb12", snapshot.contentHash());
    }

    @Test
    void theInputOrderDoesNotChangeTheHash() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new CatalogEntry("minecraft:block_" + i, i % 2 == 0, i % 3 == 0, i, "t" + i, null, "s" + i));
        }
        String expected = CatalogSnapshot.contentHash(entries);

        for (long seed = 1; seed <= 5; seed++) {
            List<CatalogEntry> shuffled = new ArrayList<>(entries);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(expected, CatalogSnapshot.contentHash(shuffled));
        }
    }

    @Test
    void keysSortOrdinally() {
        // Ordinal: '_' (0x5F) sorts after upper case and before lower case.
        CatalogEntry underscore = new CatalogEntry("minecraft:a_b", false, true, 1, null, null, null);
        CatalogEntry lower = new CatalogEntry("minecraft:ab", false, true, 1, null, null, null);

        assertEquals(List.of(underscore, lower), CatalogSnapshot.sortedByKey(List.of(lower, underscore)));
    }

    @Test
    void nullFieldsAreEmpty_andDistinctFromTheLiteralNull() {
        CatalogEntry allNull = new CatalogEntry("minecraft:x", false, false, 1, null, null, null);
        CatalogEntry literalNull = new CatalogEntry("minecraft:x", false, false, 1, "null", null, null);

        assertEquals("minecraft:x|false|false|1|||", CatalogSnapshot.canonicalText(List.of(allNull)));
        assertNotEquals(CatalogSnapshot.contentHash(List.of(allNull)), CatalogSnapshot.contentHash(List.of(literalNull)));
    }

    @Test
    void anEmptyCatalogueHashesTheEmptyText() {
        assertEquals("", CatalogSnapshot.canonicalText(List.of()));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CatalogSnapshot.contentHash(List.of()));
    }
}
