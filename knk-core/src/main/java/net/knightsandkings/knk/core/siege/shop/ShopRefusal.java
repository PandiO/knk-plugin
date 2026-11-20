package net.knightsandkings.knk.core.siege.shop;

import java.util.Objects;

/**
 * Why a purchase was refused (siege shop DESIGN § 4.1.4, § 5.5.2, P22; PandiO/knk-workspace#868). A refusal never
 * charges anything; {@link #message()} is what the player is told.
 */
public record ShopRefusal(Reason reason, String message) {

    public enum Reason {
        /** No running match with a shop for this player (or the match is over). */
        NOT_IN_MATCH,
        /** Dead, respawning or a ghost: only living members buy. */
        NOT_ALIVE,
        /** The entry is not in this match's catalogue. */
        UNKNOWN_ENTRY,
        /** Nothing can sell this kind yet (siege weapons until #784). */
        NOT_AVAILABLE,
        /** A weapon type the player has not unlocked (W32, A26). */
        LOCKED,
        /** The per-match cap is reached. */
        LIMIT_REACHED,
        /** The entry's cooldown is running. */
        COOLDOWN,
        /** No room in the inventory for the whole quantity (P22). */
        INVENTORY_FULL,
        /** The balance is below the current price. */
        NOT_ENOUGH_POINTS,
        /** The delivery failed after the charge, which was reversed (§ 5.5.2). */
        DELIVERY_FAILED
    }

    public ShopRefusal {
        Objects.requireNonNull(reason, "reason");
        message = message == null ? "" : message;
    }

    public static ShopRefusal notInMatch() {
        return new ShopRefusal(Reason.NOT_IN_MATCH, "The siege shop is open only during a running siege match.");
    }

    public static ShopRefusal notAlive() {
        return new ShopRefusal(Reason.NOT_ALIVE, "You can buy only while you are alive in the match.");
    }

    public static ShopRefusal unknownEntry() {
        return new ShopRefusal(Reason.UNKNOWN_ENTRY, "That is not sold in this match.");
    }

    public static ShopRefusal notAvailable(String what) {
        return new ShopRefusal(Reason.NOT_AVAILABLE, what + " can't be bought yet.");
    }

    public static ShopRefusal locked() {
        return new ShopRefusal(Reason.LOCKED, "Locked: unlock it in /menu → Minigames → Siege unlocks.");
    }

    public static ShopRefusal limitReached(int cap) {
        return new ShopRefusal(Reason.LIMIT_REACHED, "Limit reached: " + cap + " per match.");
    }

    public static ShopRefusal cooldown(long millisLeft) {
        return new ShopRefusal(Reason.COOLDOWN, "Cooldown: " + ShopText.seconds(millisLeft) + ".");
    }

    public static ShopRefusal inventoryFull() {
        return new ShopRefusal(Reason.INVENTORY_FULL, "Inventory full: make room for the whole stack first.");
    }

    public static ShopRefusal notEnoughPoints(long balance, long price) {
        return new ShopRefusal(Reason.NOT_ENOUGH_POINTS, "Not enough siege points: " + balance + "/" + price + ".");
    }

    public static ShopRefusal deliveryFailed() {
        return new ShopRefusal(Reason.DELIVERY_FAILED, "Something went wrong; your siege points were given back.");
    }

    /** A stable lower-case code for telemetry and logs. */
    public String code() {
        return reason.name().toLowerCase(java.util.Locale.ROOT);
    }
}
