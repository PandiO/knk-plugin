package net.knightsandkings.knk.core.lootbox;

import java.util.List;

/**
 * {@code POST api/LootboxSpawnAreas/{id}/in-game-delete}: the deleted area's region (removed by the plugin only when it
 * starts with {@code lootbox_}) and the boxes that were active in it (now Removed; the plugin clears their entities).
 */
public record KnkLootboxAreaDeleteResult(int id, String name, String world, String wgRegionId, List<Integer> removedSpawnIds) {
    public KnkLootboxAreaDeleteResult {
        removedSpawnIds = removedSpawnIds == null ? List.of() : List.copyOf(removedSpawnIds);
    }
}
