package net.knightsandkings.knk.core.siege.shop;

import java.util.List;

/**
 * Root {@code siegeShop} of the shop menus (siege shop § 4.1.3, P23; PandiO/knk-workspace#868): the viewer's balance,
 * the open category's name and the footer. The menu engine's titles are fixed text, so the balance is a header line
 * instead of part of the title.
 */
public final class ShopHeaderView {

    private final long balance;
    private final String categoryName;
    private final int categoryCount;

    public ShopHeaderView(long balance, String categoryName, int categoryCount) {
        this.balance = balance;
        this.categoryName = categoryName;
        this.categoryCount = categoryCount;
    }

    public long getBalance() {
        return balance;
    }

    /** "&7Siege points: &6650". */
    public String getBalanceLine() {
        return "&7Siege points: &6" + balance;
    }

    /** The category of {@code ctx.categoryId}: "&aConsumables"; "&aSiege shop" without one. */
    public String getCategoryName() {
        return "&a" + (categoryName == null || categoryName.isBlank() ? "Siege shop" : categoryName);
    }

    /** The empty-shop line when the match sells nothing. */
    public String getEmptyLine() {
        return categoryCount == 0 ? "&7Nothing is for sale in this match." : "";
    }

    public List<String> getFooterLines() {
        return List.of("&8" + ShopText.FOOTER);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShopHeaderView v && v.balance == balance && v.categoryCount == categoryCount
                && java.util.Objects.equals(v.categoryName, categoryName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(balance, categoryName, categoryCount);
    }
}
