package net.knightsandkings.knk.core.lootbox;

import net.knightsandkings.knk.core.domain.item.KnkArmorTrim;
import net.knightsandkings.knk.core.domain.item.KnkShieldBanner;

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
 * @param shieldBanner   PandiO/knk-workspace#232: the item's shield banner - the instance's frozen copy when there is
 *                       one, else the blueprint's; null when the API sent none (an older API, or no look at all)
 * @param armorTrim      #232: the item's armour trim, same source; null when none
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
        Instant deliveredAt,
        KnkShieldBanner shieldBanner,
        KnkArmorTrim armorTrim
) {
    public KnkLootboxClaimResult {
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    /** A claim without a look (#232): what every caller built before shields and trims were stored. */
    public KnkLootboxClaimResult(
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
        this(claimId, replay, userId, lootboxSpawnId, lootboxTypeId, boxStars, boxLabel, itemInstanceId, itemBlueprintId,
                itemName, itemGradeId, itemGradeStars, quantity, isSpecial, enchantments, announce, claimedAt, deliveredAt,
                null, null);
    }

    /** Whether the API described the item's look at all (#232); false from an API without the fields. */
    public boolean hasLook() {
        return shieldBanner != null || armorTrim != null;
    }

    public boolean isDelivered() {
        return deliveredAt != null;
    }
}
