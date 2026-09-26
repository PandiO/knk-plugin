package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxAreaDeleteResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Write side of the lootbox runtime (docs/specs/lootboxes/DESIGN.md §3.3). The API is authoritative: it decides
 * whether a box may spawn and what it is, rolls the item on a claim, and enforces the daily cap. A deliberate refusal
 * (409, 429) fails the future with a {@link net.knightsandkings.knk.core.lootbox.LootboxRejectedException}.
 * <p>
 * {@code actorUserId} is the staff member behind an admin action, sent as {@code X-Acting-User-Id} (trusted by the API
 * only with the service key); null is recorded as the system.
 */
public interface LootboxesCommandApi {

    /** {@code POST api/LootboxSpawns}: this server found a spot in an area. 409 AreaFull|GlobalFull|NoEnabledType|Disabled. */
    CompletableFuture<KnkLootboxSpawn> spawn(int areaId, String world, int x, int y, int z, String serverId);

    /** {@code POST api/LootboxSpawns/admin}: {@code /knk lootbox spawn}, ignores caps; null stars = rolled. */
    CompletableFuture<KnkLootboxSpawn> adminSpawn(Integer actorUserId, int typeId, Integer boxStars, String world, int x, int y, int z, String serverId);

    /** {@code POST api/LootboxSpawns/{id}/despawn}: Active becomes Removed. */
    CompletableFuture<KnkLootboxSpawn> despawn(Integer actorUserId, int spawnId);

    /** {@code POST api/LootboxSpawns/{id}/claim}: rolls once; the same key replays the stored result. */
    CompletableFuture<KnkLootboxClaimResult> claim(int spawnId, UUID token, int userId, String idempotencyKey);

    /** {@code POST api/LootboxClaims/{id}/delivered}: idempotent. */
    CompletableFuture<Void> markDelivered(int claimId, LootboxDeliveryMethod method, String note, Integer userId);

    /** {@code POST api/LootboxClaims/admin-give}: roll and mint without a world box; not counted against the cap. */
    CompletableFuture<KnkLootboxClaimResult> adminGive(Integer actorUserId, int userId, int typeId, Integer boxStars, String idempotencyKey);

    /** {@code POST api/LootboxSpawnAreas/in-game}: an enabled area with default limits. 409 NameTaken|RegionInUse. */
    CompletableFuture<KnkLootboxArea> createAreaInGame(Integer actorUserId, String name, String world, String wgRegionId);

    /** {@code POST api/LootboxSpawnAreas/{id}/in-game-delete}: its active boxes become Removed, the row is deleted. */
    CompletableFuture<KnkLootboxAreaDeleteResult> deleteAreaInGame(Integer actorUserId, int areaId);
}
