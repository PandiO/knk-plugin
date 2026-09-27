package net.knightsandkings.knk.core.lootbox;

import java.time.Instant;
import java.util.UUID;

/**
 * A box in the world (docs/specs/lootboxes/DESIGN.md §3.2): where it is, what it is, and until when. Mirrors
 * knk-web-api's {@code LootboxSpawnDto}. The token is the box's identity on the entities (PDC) and in the claim.
 *
 * @param boxLabel "&lt;GradeName&gt; &lt;TypeName&gt;", e.g. "Legendary Weapons Lootbox"; the plugin adds colour and stars
 * @param status   Active, Claimed, Expired or Removed
 */
public record KnkLootboxSpawn(
        int id,
        UUID token,
        int lootboxTypeId,
        String lootboxTypeName,
        String categoryName,
        int boxGradeId,
        String boxGradeName,
        int boxStars,
        String boxLabel,
        Integer spawnAreaId,
        String spawnAreaName,
        String world,
        int x,
        int y,
        int z,
        String status,
        Instant spawnedAt,
        Instant expiresAt
) {
    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now != null && !now.isBefore(expiresAt);
    }
}
