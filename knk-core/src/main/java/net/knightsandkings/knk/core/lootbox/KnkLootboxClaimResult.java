package net.knightsandkings.knk.core.lootbox;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of a claim or an admin give (docs/specs/lootboxes/DESIGN.md §3.3), also what {@code pending} returns
 * and what an idempotent replay repeats. Mirrors knk-web-api's {@code LootboxClaimResultDto}.
 *
 * @param itemInstanceId the minted {@code ItemInstance} (stamped on the item's PDC); null for stackable items
 * @param enchantments   the item's final set: the instance's rows, or the blueprint defaults for a stackable item
 * @param announce       a special or an item at the announce threshold: broadcast once
 * @param deliveredAt    set once the plugin confirmed delivery; a replay of a delivered claim must not give again
 */
public record KnkLootboxClaimResult(
        int claimId,
        boolean replay,
        int userId,
        Integer lootboxSpawnId,
        int lootboxTypeId,
        int boxStars,
        String boxLabel,
        Long itemInstanceId,
        int itemBlueprintId,
        String itemName,
        Integer itemGradeId,
        Integer itemGradeStars,
        int quantity,
        boolean isSpecial,
        List<KnkLootboxClaimEnchantment> enchantments,
        boolean announce,
        Instant claimedAt,
        Instant deliveredAt
) {
    public KnkLootboxClaimResult {
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    public boolean isDelivered() {
        return deliveredAt != null;
    }
}
