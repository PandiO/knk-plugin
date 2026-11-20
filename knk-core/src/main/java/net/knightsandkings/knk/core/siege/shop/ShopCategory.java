package net.knightsandkings.knk.core.siege.shop;

import java.util.List;

/**
 * A shop category with its visible entries, in shop order (siege shop § 5.3, P19; PandiO/knk-workspace#868).
 *
 * @param key  stable key ({@code siege-weapons}, {@code consumables}, {@code magic})
 * @param iconMaterial a material key, or null
 */
public record ShopCategory(int id, String key, String name, String iconMaterial, int sortOrder, List<ShopEntry> entries) {
    public ShopCategory {
        entries = entries == null ? List.of() : List.copyOf(entries);
        name = name == null || name.isBlank() ? (key == null ? "Category #" + id : key) : name.trim();
    }

    public ShopCategory withEntries(List<ShopEntry> newEntries) {
        return new ShopCategory(id, key, name, iconMaterial, sortOrder, newEntries);
    }
}
