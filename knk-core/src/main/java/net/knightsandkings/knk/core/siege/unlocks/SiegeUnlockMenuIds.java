package net.knightsandkings.knk.core.siege.unlocks;

/**
 * Every id of the Siege unlocks menu (siege shop DESIGN § 4.2, § 7.2.5, P08; PandiO/knk-workspace#869): reached from the
 * main menu's Siege Minigame tile (the minigames tile) through the {@code siege.overview} menu's Siege unlocks button.
 * knk-web-api's {@code MenuTemplateSeed.SiegeUnlocks.cs} references the same strings; knk-api-client's
 * {@code SiegeMenuSeedContractTest} checks them.
 */
public final class SiegeUnlockMenuIds {
    private SiegeUnlockMenuIds() {
    }

    public static final String MENU_UNLOCKS = "siege.unlocks";

    /** {@link SiegeUnlocksHeaderView}: the viewer's coins and title, and the "not during a match" line. */
    public static final String ROOT_UNLOCKS = "siegeUnlocks";

    /** {@link SiegeUnlockRowView} rows: every weapon template with the viewer's state. */
    public static final String SOURCE_UNLOCKS = "siege.unlocks";

    /** Buys the {@code templateId} param's unlock for the clicking player (refused during a match, A28). */
    public static final String ACTION_BUY = "siege.unlocks.buy";

    /** True while this session's pending confirmation is a {@link #ACTION_BUY} (the menu's Confirm/Cancel). */
    public static final String CONDITION_PURCHASE_PENDING = "siege.unlocks.purchase-pending";
}
