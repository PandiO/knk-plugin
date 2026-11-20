package net.knightsandkings.knk.core.siege.shop;

import java.util.Objects;

/**
 * One entry as a match sells it (siege shop DESIGN § 5.3, § 5.4, § 6.1.2, P06, P19; PandiO/knk-workspace#868): the
 * API's effective catalogue line, scenario overrides already applied and a weapon's price already resolved. Brake
 * fields are nullable: empty means no step, no maximum, no cooldown or no cap.
 *
 * @param source       {@code Default}, {@code Overridden} or {@code Scenario} (for the admin, not for the rules)
 * @param quantity     items per purchase (P06 "per standard amount"); at least 1
 * @param price        the base price in siege points, already resolved for weapons
 * @param iconMaterial the entry's own icon material key, or null (the blueprint's icon is used)
 */
public record ShopEntry(
        int id,
        int categoryId,
        ShopEntryKind kind,
        String source,
        Integer itemBlueprintId,
        String itemBlueprintName,
        int quantity,
        Integer siegeWeaponTemplateId,
        String siegeWeaponTemplateName,
        String displayName,
        String iconMaterial,
        long price,
        Long priceStep,
        ShopCurve priceCurve,
        Double priceGrowthFactor,
        Long priceMax,
        Double cooldownSeconds,
        Double cooldownStepSeconds,
        ShopCurve cooldownCurve,
        Double cooldownGrowthFactor,
        Double cooldownMaxSeconds,
        Integer maxPerMatch,
        int sortOrder
) {
    public ShopEntry {
        Objects.requireNonNull(kind, "kind");
        quantity = Math.max(1, quantity);
        price = Math.max(0, price);
        priceCurve = priceCurve == null ? ShopCurve.NONE : priceCurve;
        cooldownCurve = cooldownCurve == null ? ShopCurve.NONE : cooldownCurve;
    }

    /** The name the shop shows: the entry's own, else the blueprint's or the template's, else "Entry #id". */
    public String name() {
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        String target = kind == ShopEntryKind.ITEM ? itemBlueprintName : siegeWeaponTemplateName;
        return target != null && !target.isBlank() ? target.trim() : "Entry #" + id;
    }

    /** The entry has a per-match cap ({@code MaxPerMatch}). */
    public boolean capped() {
        return maxPerMatch != null && maxPerMatch >= 0;
    }
}
