package net.knightsandkings.knk.core.siege.shop;

import java.util.UUID;

/**
 * One purchase that passed every check of {@link ShopPurchaseService}, handed to the kind's
 * {@link ShopPurchaseHandler} (siege shop § 5.5, P22, W67; PandiO/knk-workspace#868). The handler charges through
 * {@link #charge()} and, when its delivery fails after the charge, gives the points back with {@link #reverse()}.
 * Main thread only.
 */
public interface ShopPurchaseTicket {

    UUID buyer();

    ShopEntry entry();

    /** The price the next charge would take now (the entry's current escalated price). */
    long price();

    /**
     * Re-checks the match, cap, cooldown and balance (a weapon flow may charge seconds after the click), then charges
     * the ledger with retry id {@code shop:{matchId}:{player}:{entryId}:{n}} and counts the purchase (price step,
     * cooldown, cap). Calling it again returns the first answer; nothing is charged twice.
     */
    ShopPurchaseResult charge();

    /**
     * The delivery failed after {@link #charge()}: the points come back with a ledger credit (retry id plus
     * {@code :reversal}) and the purchase no longer counts. Does nothing without an accepted charge, and only once.
     */
    void reverse();
}
