package net.knightsandkings.knk.core.production.shop;

/**
 * The sell menu's slots, kept from the legacy V1 "Sell items to X" menu (legacy inventory-menu-screens § 3.7.2): 36
 * slots; 0 the player's coins (Financial), 3 the Sell chest with the total, 4 the shop's info, 5 the instructions,
 * 8 Back, and slots 19 to 25 hold what the player picked.
 */
public final class SellMenuLayout {

    public static final int SIZE = 36;
    public static final int COINS_SLOT = 0;
    public static final int SELL_SLOT = 3;
    public static final int INFO_SLOT = 4;
    public static final int HELP_SLOT = 5;
    public static final int BACK_SLOT = 8;
    public static final int FIRST_PICK_SLOT = 19;

    private SellMenuLayout() {
    }

    /** The slot of the pick at {@code index}. */
    public static int pickSlot(int index) {
        return FIRST_PICK_SLOT + index;
    }

    /** The pick index of a top-inventory slot, or -1 outside the drop zone. */
    public static int pickIndex(int slot) {
        int index = slot - FIRST_PICK_SLOT;
        return index >= 0 && index < SellBasket.CAPACITY ? index : -1;
    }
}
