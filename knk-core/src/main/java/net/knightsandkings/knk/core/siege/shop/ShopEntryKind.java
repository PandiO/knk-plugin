package net.knightsandkings.knk.core.siege.shop;

import java.util.Locale;
import java.util.Optional;

/**
 * What a shop entry sells (siege shop P19; PandiO/knk-workspace#868). Each kind has its own
 * {@link ShopPurchaseHandler}; the API sends {@code Item} and {@code SiegeWeapon}.
 */
public enum ShopEntryKind {
    /** An item blueprint and a quantity: bought at once ({@code ItemPurchaseHandler}). */
    ITEM,
    /** A siege weapon template: bought through the weapon's spot picker (siege weapons phase 3, #784). */
    SIEGE_WEAPON;

    /** The API's name; empty for an unknown kind (a later kind such as MagicAction), so that entry is left out. */
    public static Optional<ShopEntryKind> fromApi(String value) {
        if (value == null) return Optional.empty();
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "item" -> Optional.of(ITEM);
            case "siegeweapon", "siege_weapon" -> Optional.of(SIEGE_WEAPON);
            default -> Optional.empty();
        };
    }
}
