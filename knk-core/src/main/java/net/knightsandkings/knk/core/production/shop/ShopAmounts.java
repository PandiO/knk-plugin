package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * The amount of one purchase, as the legacy item information menu's "Amount" chest cycles it (V1
 * {@code ItemFrameAdd.setItemStep}; legacy inventory-menu-screens § 3.7.3). A unique item is always one. The plugin keeps
 * the chosen step per player and item (action {@code shops.amount}), since a variable root can't read session state.
 */
public final class ShopAmounts {

    /** The steps the Amount button cycles through. */
    public static final List<Long> STEPS = List.of(1L, 8L, 16L, 32L, 64L);
    /** The API's limit per purchase. */
    public static final long MAX = 100_000L;

    private ShopAmounts() {
    }

    /** The step after {@code current} (back to 1 after the last, and from any value that isn't a step). */
    public static long next(long current) {
        int index = STEPS.indexOf(current);
        return index < 0 || index == STEPS.size() - 1 ? STEPS.get(0) : STEPS.get(index + 1);
    }

    /** The amount to buy: the chosen step (at least 1), 1 for a unique item, capped by limited stock. */
    public static long of(long chosen, ShopOfferItem item) {
        long amount = Math.max(1L, Math.min(MAX, chosen));
        if (item == null) {
            return amount;
        }
        if (item.unique()) {
            return 1L;
        }
        if (item.stock() != null && item.stock() > 0) {
            amount = Math.min(amount, item.stock());
        }
        return amount;
    }
}
