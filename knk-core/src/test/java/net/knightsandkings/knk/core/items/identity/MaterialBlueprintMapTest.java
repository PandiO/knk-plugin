package net.knightsandkings.knk.core.items.identity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialBlueprintMapTest {

    @Test
    void normalisesKeys_andSkipsBadEntries() {
        MaterialBlueprintMap map = MaterialBlueprintMap.of(Arrays.asList(
                new MaterialBlueprintMap.Entry("OAK_LOG", 10, true),
                null,
                new MaterialBlueprintMap.Entry("minecraft:stone", 0, true),
                new MaterialBlueprintMap.Entry(" ", 5, true)));

        assertEquals(1, map.size());
        assertEquals(10, map.blueprintIdOf("minecraft:oak_log"));
        assertEquals(10, map.blueprintIdOf("oak_log"));
        assertTrue(map.find("Oak_Log").orElseThrow().shadow());
        assertNull(map.blueprintIdOf("minecraft:stone"));
        assertNull(map.blueprintIdOf(null));
        assertTrue(MaterialBlueprintMap.EMPTY.isEmpty());
    }
}
