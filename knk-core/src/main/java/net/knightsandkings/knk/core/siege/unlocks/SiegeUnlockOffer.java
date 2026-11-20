package net.knightsandkings.knk.core.siege.unlocks;

/**
 * One weapon template for one player, as {@code GET api/SiegeWeaponUnlocks/by-user/{userId}} returns it (siege shop
 * DESIGN § 4.2, § 5.6; PandiO/knk-workspace#869).
 *
 * @param minTitleName the minimum title's name, null without a minimum
 * @param state        {@link State}
 */
public record SiegeUnlockOffer(int templateId, String name, String kind, int unlockPrice, Integer minTitleLevel,
                               String minTitleName, State state) {

    /** The API's states, in the order it checks them. */
    public enum State {
        UNLOCKED, NEEDS_TITLE, NEEDS_COINS, UNLOCKABLE;

        /** {@code Unlocked}, {@code NeedsTitle}, {@code NeedsCoins}, {@code Unlockable}; anything else counts as unlockable. */
        public static State fromApi(String value) {
            if (value == null) return UNLOCKABLE;
            return switch (value) {
                case "Unlocked" -> UNLOCKED;
                case "NeedsTitle" -> NEEDS_TITLE;
                case "NeedsCoins" -> NEEDS_COINS;
                default -> UNLOCKABLE;
            };
        }
    }
}
