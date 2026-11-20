package net.knightsandkings.knk.core.siege.shop;

import java.util.List;

/** Catalogue lines of the § 12.2 seed for the shop tests (PandiO/knk-workspace#868). */
public final class ShopFixtures {

    private ShopFixtures() {
    }

    public static final int CONSUMABLES = 2;
    public static final int WEAPONS = 1;
    public static final int MAGIC = 3;

    /** Ender pearl ×1: 300, +50 linear, cooldown 20 s +10 s exponential ×1.5, cap 8. */
    public static ShopEntry pearl() {
        return new ShopEntry(11, CONSUMABLES, ShopEntryKind.ITEM, "Default", 101, "Ender Pearl", 1, null, null, null,
                "minecraft:ender_pearl", 300, 50L, ShopCurve.LINEAR, null, null, 20.0, 10.0, ShopCurve.EXPONENTIAL, 1.5,
                null, 8, 0);
    }

    /** Splash potion of healing II ×1: 150, +25 linear, cooldown 10 s, cap 10. */
    public static ShopEntry healing() {
        return new ShopEntry(12, CONSUMABLES, ShopEntryKind.ITEM, "Default", 102, "Splash Potion of Healing II", 1, null,
                null, null, null, 150, 25L, ShopCurve.LINEAR, null, null, 10.0, null, ShopCurve.NONE, null, null, 10, 1);
    }

    /** Arrows ×16 at a fixed 40, no brakes. */
    public static ShopEntry arrows() {
        return new ShopEntry(13, CONSUMABLES, ShopEntryKind.ITEM, "Scenario", 103, "Arrow", 16, null, null, null, null,
                40, null, ShopCurve.NONE, null, null, null, null, ShopCurve.NONE, null, null, null, 2);
    }

    /** A trebuchet at its template's 200. */
    public static ShopEntry trebuchet() {
        return new ShopEntry(21, WEAPONS, ShopEntryKind.SIEGE_WEAPON, "Default", null, null, 1, 5, "Trebuchet", null, null,
                200, null, ShopCurve.NONE, null, null, null, null, ShopCurve.NONE, null, null, null, 0);
    }

    public static ShopEntry withPrice(ShopEntry e, long price, Long step, ShopCurve curve, Double factor, Long max) {
        return new ShopEntry(e.id(), e.categoryId(), e.kind(), e.source(), e.itemBlueprintId(), e.itemBlueprintName(),
                e.quantity(), e.siegeWeaponTemplateId(), e.siegeWeaponTemplateName(), e.displayName(), e.iconMaterial(),
                price, step, curve, factor, max, e.cooldownSeconds(), e.cooldownStepSeconds(), e.cooldownCurve(),
                e.cooldownGrowthFactor(), e.cooldownMaxSeconds(), e.maxPerMatch(), e.sortOrder());
    }

    public static ShopEntry withCooldown(ShopEntry e, Double base, Double step, ShopCurve curve, Double factor, Double max) {
        return new ShopEntry(e.id(), e.categoryId(), e.kind(), e.source(), e.itemBlueprintId(), e.itemBlueprintName(),
                e.quantity(), e.siegeWeaponTemplateId(), e.siegeWeaponTemplateName(), e.displayName(), e.iconMaterial(),
                e.price(), e.priceStep(), e.priceCurve(), e.priceGrowthFactor(), e.priceMax(), base, step, curve, factor,
                max, e.maxPerMatch(), e.sortOrder());
    }

    /** Siege weapons (trebuchet), Consumables (pearl, healing, arrows) and an empty Magic category. */
    public static ShopCatalogue catalogue() {
        return new ShopCatalogue("Siege", 4, List.of(
                new ShopCategory(MAGIC, "magic", "Magic", "BLAZE_ROD", 2, List.of()),
                new ShopCategory(CONSUMABLES, "consumables", "Consumables", "POTION", 1, List.of(arrows(), healing(), pearl())),
                new ShopCategory(WEAPONS, "siege-weapons", "Siege weapons", "CROSSBOW", 0, List.of(trebuchet()))));
    }
}
