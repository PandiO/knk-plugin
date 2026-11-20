package net.knightsandkings.knk.core.siege.unlocks;

/** Player-facing text of the Siege unlocks menu (siege shop DESIGN § 4.2; PandiO/knk-workspace#869). */
public final class SiegeUnlockText {
    private SiegeUnlockText() {
    }

    /** A28: unlocks are bought only outside a match (P08). */
    public static final String NOT_DURING_A_MATCH = "Siege weapons can't be unlocked during a match. Unlock them after it.";

    public static String purchasePrompt(SiegeUnlockOffer offer) {
        return "Unlock the " + offer.name() + " for " + offer.unlockPrice() + " coins? Click Confirm in the menu.";
    }

    /** The chat line after a purchase. */
    public static String result(SiegeUnlockPurchaseResult result) {
        if (result instanceof SiegeUnlockPurchaseResult.Unlocked u) {
            if (u.alreadyUnlocked()) return "You have already unlocked the " + u.templateName() + ".";
            return "Unlocked the " + u.templateName() + (u.coinsPaid() > 0 ? " for " + u.coinsPaid() + " coins" : "")
                    + ". You can buy it in siege matches now.";
        }
        SiegeUnlockPurchaseResult.Refused r = (SiegeUnlockPurchaseResult.Refused) result;
        return r.message() == null || r.message().isBlank() ? "That unlock was refused." : r.message();
    }
}
