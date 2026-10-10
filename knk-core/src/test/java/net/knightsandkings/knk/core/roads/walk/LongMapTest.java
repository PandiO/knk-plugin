package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LongMapTest {

    @Test
    void storesOverwritesAndGrowsAcrossPackedBlockKeys() {
        LongMap<String> map = new LongMap<>(1);
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                map.put(BlockKey.pack(x, 64, z), x + "," + z);
            }
        }
        assertEquals(6400, map.size());
        assertEquals("-40,-40", map.get(BlockKey.pack(-40, 64, -40)));
        assertEquals("39,39", map.get(BlockKey.pack(39, 64, 39)));
        assertNull(map.get(BlockKey.pack(39, 65, 39)));

        map.put(BlockKey.pack(0, 64, 0), "again");
        assertEquals(6400, map.size());
        assertEquals("again", map.get(BlockKey.pack(0, 64, 0)));
        assertNull(map.get(BlockKey.pack(0, 0, 0)), "key 0 is a real key, not a sentinel");
    }
}
