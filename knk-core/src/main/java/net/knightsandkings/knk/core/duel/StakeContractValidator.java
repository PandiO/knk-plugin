package net.knightsandkings.knk.core.duel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Contract checks the plugin can make on its own (DESIGN §6.3, §7.6): advisory when an offer is sent,
 * binding at lock-in. Item presence ({@code isSimilar} + amount), eligibility (D14) and the pair/daily
 * limits (§10.2) are checked by the runtime and the API, not here.
 *
 * <p><b>Stake ratio</b> ({@code MinStakeRatioPercent}): the smaller coin stake must be at least that
 * percentage of the larger, so "I stake 10,000, you stake 0" is refused. When the side with fewer coins
 * stakes items, the items are its collateral and the ratio isn't applied, so DESIGN §6.2's example
 * "I stake my diamond sword, you stake 500 coins" stays possible. (Interpretation recorded in the
 * P3 report; the API applies its own copy of the rule at lock-in.)
 */
public final class StakeContractValidator {
    private StakeContractValidator() {}

    /**
     * Checks a contract against the arena's settings.
     *
     * @param blocked true for an item that can't be staked (config blocklist, filled containers, loaned
     *                kit items, items held for another feature; DESIGN §7.6); null = nothing blocked
     * @return every violation found, in a stable order; empty when the contract is valid
     */
    public static List<StakeViolation> validate(StakeContract contract, DuelArenaSettings settings,
                                                Predicate<StakeItem> blocked) {
        List<StakeViolation> out = new ArrayList<>();
        GearMode mode = contract.gearMode();
        if (!settings.allows(mode)) {
            out.add(new StakeViolation(StakeViolation.Code.GEAR_MODE_NOT_ALLOWED, null, mode.name()));
        }
        if (mode.isKitMode() && settings.eligibleKitIds().isEmpty()) {
            out.add(new StakeViolation(StakeViolation.Code.NO_ELIGIBLE_KITS, null, null));
        }
        if (mode == GearMode.FIXED_KIT) {
            if (contract.fixedKitId() == null) {
                out.add(new StakeViolation(StakeViolation.Code.FIXED_KIT_MISSING, null, null));
            } else if (!settings.isKitEligible(contract.fixedKitId())) {
                out.add(new StakeViolation(StakeViolation.Code.FIXED_KIT_NOT_ELIGIBLE, null,
                        String.valueOf(contract.fixedKitId())));
            }
        } else if (contract.fixedKitId() != null) {
            out.add(new StakeViolation(StakeViolation.Code.KIT_NOT_EXPECTED, null, String.valueOf(contract.fixedKitId())));
        }
        for (DuelSide side : DuelSide.values()) {
            checkSide(side, contract.stakeOf(side), settings, blocked, out);
        }
        checkRatio(contract, settings, out);
        return List.copyOf(out);
    }

    /**
     * "Only the owner may add items; the author may remove the other side's items" (DESIGN §6.2).
     *
     * @param previous the contract being countered, or null for offer #1 (nothing staked yet)
     * @param author   who wrote {@code next}
     */
    public static List<StakeViolation> validateItemOwnership(StakeContract previous, StakeContract next, DuelSide author) {
        List<StakeViolation> out = new ArrayList<>();
        DuelSide other = author.other();
        Set<String> before = new HashSet<>();
        if (previous != null) {
            for (StakeItem item : previous.stakeOf(other).items()) before.add(item.refId());
        }
        for (StakeItem item : next.stakeOf(other).items()) {
            if (!before.contains(item.refId())) {
                out.add(new StakeViolation(StakeViolation.Code.ITEM_ADDED_BY_OTHER_SIDE, other, item.refId()));
            }
        }
        // An unchanged ref id with a different amount or material is an edit by the author, i.e. an add.
        if (previous != null) {
            List<StakeItem> earlier = previous.stakeOf(other).items();
            for (StakeItem item : next.stakeOf(other).items()) {
                if (before.contains(item.refId()) && !earlier.contains(item)) {
                    out.add(new StakeViolation(StakeViolation.Code.ITEM_ADDED_BY_OTHER_SIDE, other, item.refId()));
                }
            }
        }
        return List.copyOf(out);
    }

    /** {@link #validate} plus {@link #validateItemOwnership}. */
    public static List<StakeViolation> validateOffer(StakeContract previous, StakeContract next, DuelSide author,
                                                     DuelArenaSettings settings, Predicate<StakeItem> blocked) {
        List<StakeViolation> out = new ArrayList<>(validate(next, settings, blocked));
        out.addAll(validateItemOwnership(previous, next, author));
        return List.copyOf(out);
    }

    private static void checkSide(DuelSide side, Stake stake, DuelArenaSettings settings,
                                  Predicate<StakeItem> blocked, List<StakeViolation> out) {
        long coins = stake.coins();
        if (coins < 0) {
            out.add(new StakeViolation(StakeViolation.Code.NEGATIVE_COINS, side, null));
        } else if (coins > 0 && !settings.allowCoinStakes()) {
            out.add(new StakeViolation(StakeViolation.Code.COINS_NOT_ALLOWED, side, null));
        } else if (coins > settings.maxCoinStake()) {
            out.add(new StakeViolation(StakeViolation.Code.COINS_OVER_MAX, side, String.valueOf(settings.maxCoinStake())));
        }
        List<StakeItem> items = stake.items();
        if (items.isEmpty()) return;
        if (!settings.allowItemStakes()) {
            out.add(new StakeViolation(StakeViolation.Code.ITEMS_NOT_ALLOWED, side, null));
            return;
        }
        if (items.size() > settings.maxItemStacks()) {
            out.add(new StakeViolation(StakeViolation.Code.TOO_MANY_STACKS, side, String.valueOf(settings.maxItemStacks())));
        }
        Set<String> seen = new HashSet<>();
        for (StakeItem item : items) {
            if (!seen.add(item.refId())) {
                out.add(new StakeViolation(StakeViolation.Code.DUPLICATE_ITEM, side, item.refId()));
            }
            if (blocked != null && blocked.test(item)) {
                out.add(new StakeViolation(StakeViolation.Code.ITEM_BLOCKED, side, item.material()));
            }
        }
    }

    private static void checkRatio(StakeContract contract, DuelArenaSettings settings, List<StakeViolation> out) {
        int ratio = settings.minStakeRatioPercent();
        if (ratio == 0) return;
        long a = contract.challenger().coins();
        long b = contract.opponent().coins();
        if (a < 0 || b < 0 || a == b) return;
        DuelSide smaller = a < b ? DuelSide.CHALLENGER : DuelSide.OPPONENT;
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        if (!contract.stakeOf(smaller).items().isEmpty()) return; // items are the collateral
        long minimum = minimumMatchingStake(high, ratio);
        if (low < minimum) {
            out.add(new StakeViolation(StakeViolation.Code.STAKE_RATIO, smaller, String.valueOf(minimum)));
        }
    }

    /** The smallest coin stake that matches {@code larger} at {@code ratioPercent} (rounded up). */
    public static long minimumMatchingStake(long larger, int ratioPercent) {
        if (larger <= 0 || ratioPercent <= 0) return 0;
        return (larger * ratioPercent + 99) / 100;
    }
}
