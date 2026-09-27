package net.knightsandkings.knk.core.lootbox;

/**
 * A world box picked up (docs/specs/lootboxes/DESIGN.md §3.8, {@code POST api/LootboxSpawns/{id}/pickup}): the clicking
 * player takes the box as a token item and opens it later. {@code replay} = this player had already taken it (a
 * retried click); the same token comes back.
 */
public record KnkLootboxPickup(boolean replay, int spawnId, KnkLootboxToken token) {
}
