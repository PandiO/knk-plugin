package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the lootbox runtime (docs/specs/lootboxes/DESIGN.md §3.3). All of it needs the plugin's service key.
 */
public interface LootboxesQueryApi {

    /** {@code GET api/LootboxSpawns/runtime-config}: global settings, enabled types, grades, every area. */
    CompletableFuture<KnkLootboxRuntimeConfig> getRuntimeConfig();

    /** {@code GET api/LootboxSpawns/active}: the active boxes on every server. */
    CompletableFuture<List<KnkLootboxSpawn>> getActive();

    /** {@code GET api/LootboxClaims/pending?userId=}: the user's undelivered claims older than 30 s. */
    CompletableFuture<List<KnkLootboxClaimResult>> getPending(int userId);

    /** {@code GET api/LootboxTokens/undelivered?userId=}: the user's issued token items not handed over yet. */
    CompletableFuture<List<KnkLootboxToken>> getUndeliveredTokens(int userId);

    /** {@code GET api/LootboxTypes/{id}/odds?boxStars=}; null stars = the type's highest box grade. */
    CompletableFuture<KnkLootboxOdds> getOdds(int lootboxTypeId, Integer boxStars);

    /**
     * {@code POST api/LootboxTokens/status}: what became of these token items, by token (Issued | Redeemed | Revoked |
     * Unknown). The join scan removes revoked and already-opened copies.
     */
    CompletableFuture<Map<UUID, String>> getTokenStatuses(List<UUID> tokens);
}
