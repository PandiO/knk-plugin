package net.knightsandkings.knk.core.siege.shop;

import java.util.List;
import java.util.Objects;

/** One shop category, a row of {@code siege.shop-categories} (siege shop § 4.1.3; PandiO/knk-workspace#868). */
public final class ShopCategoryView {

    private final ShopCategory category;

    public ShopCategoryView(ShopCategory category) {
        this.category = Objects.requireNonNull(category, "category");
    }

    /** The {@code ctx.categoryId} of the category menu. */
    public int getCategoryId() {
        return category.id();
    }

    public String getName() {
        return "&a" + category.name();
    }

    public String getMaterial() {
        return category.iconMaterial() == null || category.iconMaterial().isBlank() ? "CHEST" : category.iconMaterial();
    }

    public List<String> getLines() {
        int n = category.entries().size();
        return List.of("&7" + n + (n == 1 ? " offer" : " offers"), "", "&aClick to open");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShopCategoryView v && v.category.equals(category);
    }

    @Override
    public int hashCode() {
        return category.hashCode();
    }
}
