package net.knightsandkings.knk.core.lootbox;

import java.util.List;
import java.util.UUID;

/**
 * Lootbox changes made outside the game that the server applies at once (a {@code LootboxWorldChanged} player
 * notification, docs/specs/lootboxes/DESIGN.md §3.9): world boxes that are gone (a web despawn or area delete) and
 * token items that were revoked.
 */
public record KnkLootboxWorldChange(List<Integer> removedSpawnIds, List<UUID> revokedTokens) {
    public KnkLootboxWorldChange {
        removedSpawnIds = removedSpawnIds == null ? List.of() : List.copyOf(removedSpawnIds);
        revokedTokens = revokedTokens == null ? List.of() : List.copyOf(revokedTokens);
    }
}
