package net.knightsandkings.knk.core.siege.shop;

/** The answer to a purchase (siege shop § 5.5; PandiO/knk-workspace#868). */
public sealed interface ShopPurchaseResult {

    /**
     * Charged and delivered.
     *
     * @param price          the price charged
     * @param purchaseNumber this player's purchase number of the entry this match (1 for the first)
     * @param chargeId       the ledger retry id {@code shop:{matchId}:{player}:{entryId}:{n}}
     */
    record Bought(ShopEntry entry, long price, int purchaseNumber, long balanceAfter, String chargeId) implements ShopPurchaseResult {
    }

    /** Nothing was charged (or a charge was reversed); the refusal says why. */
    record Refused(ShopRefusal refusal) implements ShopPurchaseResult {
    }

    /**
     * Every check passed and the kind's handler took over with its own flow (a siege weapon's spot picker, #784); it
     * charges through its {@link ShopPurchaseTicket} once its own checks pass.
     */
    record Started(ShopEntry entry) implements ShopPurchaseResult {
    }
}
