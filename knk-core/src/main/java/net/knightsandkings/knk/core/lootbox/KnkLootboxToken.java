package net.knightsandkings.knk.core.lootbox;

import java.util.UUID;

/**
 * A lootbox token item (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5): v1's "Sword Box" consumable, issued by
 * the API. {@code token} goes into the item's PDC and is the item's only identity; opening the item redeems it
 * through the same claim path as a world box. Mirrors knk-web-api's {@code LootboxTokenDto}.
 *
 * @param boxLabel "&lt;GradeName&gt; &lt;TypeName&gt;", e.g. "Legendary Weapons Lootbox"
 * @param status   Issued | Redeemed | Revoked
 * @param reason   Admin | PremiumTier | Kit | PvpKill | Referral | Other | WorldPickup (a world box picked up, DESIGN.md §3.8)
 */
public record KnkLootboxToken(
        int id,
        UUID token,
        int lootboxTypeId,
        String lootboxTypeName,
        String categoryName,
        int boxStars,
        String boxLabel,
        String status,
        String reason,
        Integer issuedToUserId
) {
    public static final String REASON_ADMIN = "Admin";
    public static final String REASON_PVP_KILL = "PvpKill";
    public static final String REASON_REFERRAL = "Referral";
    public static final String REASON_WORLD_PICKUP = "WorldPickup";

    public static final String STATUS_ISSUED = "Issued";
    public static final String STATUS_REDEEMED = "Redeemed";
    public static final String STATUS_REVOKED = "Revoked";
}
