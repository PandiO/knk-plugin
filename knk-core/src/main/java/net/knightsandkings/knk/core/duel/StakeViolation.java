package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * One reason a contract can't be sent or locked in (DESIGN §6.3: "each failed check names the reason").
 *
 * @param side   the side the violation is about, or null for the contract as a whole
 * @param detail a fact for the message (a material, a limit), or null
 */
public record StakeViolation(Code code, DuelSide side, String detail) {
    public enum Code {
        GEAR_MODE_NOT_ALLOWED,
        /** {@code FIXED_KIT} without a kit. */
        FIXED_KIT_MISSING,
        FIXED_KIT_NOT_ELIGIBLE,
        /** A kit was named for a mode other than {@code FIXED_KIT}. */
        KIT_NOT_EXPECTED,
        /** A kit mode on an arena with no eligible kits. */
        NO_ELIGIBLE_KITS,
        COINS_NOT_ALLOWED,
        ITEMS_NOT_ALLOWED,
        NEGATIVE_COINS,
        /** More than {@code MaxCoinStake}; {@code detail} is the maximum. */
        COINS_OVER_MAX,
        /** The side's stake value (coins + item value) is below the ratio; {@code detail} is the minimum value. */
        STAKE_RATIO,
        /** More than {@code MaxItemStacks}; {@code detail} is the maximum. */
        TOO_MANY_STACKS,
        /** The blocklist refused an item; {@code detail} is its material. */
        ITEM_BLOCKED,
        /** The same item reference twice on one side; {@code detail} is the ref id. */
        DUPLICATE_ITEM,
        /** Only the owner may add items to their side (DESIGN §6.2); {@code detail} is the ref id. */
        ITEM_ADDED_BY_OTHER_SIDE
    }

    public StakeViolation {
        Objects.requireNonNull(code, "code");
    }
}
