package net.knightsandkings.knk.core.siege.unlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One template, a row of {@code siege.unlocks} (siege shop DESIGN § 4.2.1; PandiO/knk-workspace#869): name, coin price,
 * minimum title and "Unlocked" / "Unlock for 1000 coins" / "Needs title Squire". An unlockable row's click asks for a
 * confirmation first ({@link #getCanUnlock()}); any other row's click goes straight to the purchase action, which
 * answers why not. A value object: any change makes the menu re-resolve the row.
 */
public final class SiegeUnlockRowView {

    private final SiegeUnlockOffer offer;
    private final long coins;
    private final boolean inMatch;

    public SiegeUnlockRowView(SiegeUnlockOffer offer, long coins, boolean inMatch) {
        this.offer = Objects.requireNonNull(offer, "offer");
        this.coins = coins;
        this.inMatch = inMatch;
    }

    public SiegeUnlockOffer offer() {
        return offer;
    }

    /** The {@code siege.unlocks.buy} param. */
    public int getTemplateId() {
        return offer.templateId();
    }

    public String getMaterial() {
        return switch (offer.state()) {
            case UNLOCKED -> "LIME_DYE";
            case UNLOCKABLE -> "CROSSBOW";
            default -> "GRAY_DYE";
        };
    }

    public String getName() {
        return (offer.state() == SiegeUnlockOffer.State.UNLOCKED ? "&a" : "&e") + offer.name();
    }

    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&7Unlock price: &6" + offer.unlockPrice() + " coins");
        lines.add("&7Minimum title: &f" + (offer.minTitleName() == null ? "none" : offer.minTitleName()));
        lines.add("");
        switch (offer.state()) {
            case UNLOCKED -> lines.add("&aUnlocked");
            case NEEDS_TITLE -> lines.add("&cNeeds title " + offer.minTitleName());
            case NEEDS_COINS -> lines.add("&cNot enough coins: " + coins + "/" + offer.unlockPrice());
            case UNLOCKABLE -> lines.add(inMatch ? "&c" + SiegeUnlockText.NOT_DURING_A_MATCH
                    : "&aUnlock for " + offer.unlockPrice() + " coins");
        }
        return lines;
    }

    /** "true" when a click should ask to confirm a purchase: unlockable and not in a match. */
    public boolean getCanUnlock() {
        return offer.state() == SiegeUnlockOffer.State.UNLOCKABLE && !inMatch;
    }

    public String getPurchasePrompt() {
        return SiegeUnlockText.purchasePrompt(offer);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SiegeUnlockRowView v && v.offer.equals(offer) && v.coins == coins && v.inMatch == inMatch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offer, coins, inMatch);
    }
}
