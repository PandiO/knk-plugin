package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: the active-box cache keyed by id, token and chunk. */
class ActiveLootboxCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    static KnkLootboxSpawn spawn(int id, Integer areaId, String world, int x, int z, Instant expiresAt) {
        return new KnkLootboxSpawn(id, UUID.nameUUIDFromBytes(("box" + id).getBytes()), 1, "Weapons Lootbox", "Weapons",
                5, "Legendary", 5, "Legendary Weapons Lootbox", areaId, "spawn", world, x, 64, z, "Active", NOW, expiresAt);
    }

    private final ActiveLootboxCache cache = new ActiveLootboxCache();

    @Test
    void indexesByIdTokenAndChunk() {
        KnkLootboxSpawn a = spawn(1, 7, "world", 17, -1, NOW.plusSeconds(60));
        KnkLootboxSpawn b = spawn(2, 7, "world", 31, -16, NOW.plusSeconds(60));
        KnkLootboxSpawn c = spawn(3, null, "world_nether", 17, -1, NOW.plusSeconds(60));
        cache.replaceAll(List.of(a, b, c));

        assertEquals(a, cache.byId(1).orElseThrow());
        assertEquals(b, cache.byToken(b.token()).orElseThrow());
        // x 17 and 31 are both chunk 1; z -1 is chunk -1, z -16 too.
        assertEquals(2, cache.inChunk("world", 1, -1).size());
        assertEquals(List.of(c), cache.inChunk("world_nether", 1, -1));
        assertTrue(cache.inChunk("world", 0, 0).isEmpty());
        assertEquals(2, cache.countInArea(7));
    }

    @Test
    void replaceAll_returnsTheBoxesThatWentAway() {
        KnkLootboxSpawn a = spawn(1, 7, "world", 0, 0, NOW.plusSeconds(60));
        KnkLootboxSpawn b = spawn(2, 7, "world", 0, 0, NOW.plusSeconds(60));
        cache.replaceAll(List.of(a, b));

        List<KnkLootboxSpawn> gone = cache.replaceAll(List.of(b, spawn(3, 7, "world", 0, 0, NOW.plusSeconds(60))));

        assertEquals(List.of(a), gone);
        assertFalse(cache.isActiveToken(a.token()));
        assertEquals(2, cache.inChunk("world", 0, 0).size());
    }

    @Test
    void remove_clearsEveryIndex() {
        KnkLootboxSpawn a = spawn(1, 7, "world", 0, 0, NOW.plusSeconds(60));
        cache.put(a);

        assertEquals(a, cache.remove(1).orElseThrow());
        assertTrue(cache.remove(1).isEmpty());
        assertTrue(cache.byToken(a.token()).isEmpty());
        assertTrue(cache.inChunk("world", 0, 0).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void put_movesABoxThatChangedChunk() {
        cache.put(spawn(1, 7, "world", 0, 0, NOW.plusSeconds(60)));
        cache.put(spawn(1, 7, "world", 100, 0, NOW.plusSeconds(60)));

        assertTrue(cache.inChunk("world", 0, 0).isEmpty());
        assertEquals(1, cache.inChunk("world", 6, 0).size());
    }

    @Test
    void removeExpired_takesOnlyExpiredBoxes() {
        KnkLootboxSpawn expired = spawn(1, 7, "world", 0, 0, NOW);
        KnkLootboxSpawn alive = spawn(2, 7, "world", 0, 0, NOW.plusSeconds(1));
        cache.replaceAll(List.of(expired, alive));

        assertEquals(List.of(expired), cache.removeExpired(NOW));
        assertEquals(List.of(alive), cache.all());
    }
}
