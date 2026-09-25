package net.knightsandkings.knk.core.domain.item;

import java.util.List;

/**
 * Bukkit-free catalog view of a Kit (docs/specs/kits/DESIGN.md §2.1), mirroring
 * {@link KnkItemBlueprint}'s separation pattern. Field names mirror knk-web-api's
 * {@code KitDto} exactly. Every equipment-slot id is nullable - a Kit can be armor-only,
 * weapon-only, or consumables-only (DESIGN.md §2.1's cascade-delete/null-safety fix).
 */
public record KnkKit(
        Integer id,
        String name,
        String description,
        Integer helmetId,
        Integer chestplateId,
        Integer leggingsId,
        Integer bootsId,
        Integer shieldId,
        Integer handId,
        List<KnkKitContent> contents,
        Integer minTitleBracketId,
        Integer requiredPermissionGroupId,
        String requiredPermissionNode,
        boolean grantOnFirstJoin,
        int cooldownSeconds,
        Integer costAmount,
        // "Coins" or "Gems" (KitCostCurrency, knk-web-api). Kept as the raw wire value - the
        // plugin never charges anything itself (that's server-side, inside ClaimKitAsync), it
        // only ever displays this back to a player via /kit list.
        String costCurrency,
        boolean isSinglePurchasePremium,
        Integer premiumPriceGems
) {}
