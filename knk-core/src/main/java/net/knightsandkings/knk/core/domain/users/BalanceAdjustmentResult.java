package net.knightsandkings.knk.core.domain.users;

import java.util.List;

/**
 * Result of UsersCommandApi.adjustBalanceById - the new balances, what each change did on the
 * server (the numbers to show, never a locally computed value) and, if an XP change crossed one
 * or more title brackets, everything needed to show one consolidated promotion/demotion
 * notification (see TitleChangeResult).
 */
public record BalanceAdjustmentResult(
    int newCoins,
    int newGems,
    int newExperiencePoints,
    TitleChangeResult titleChange, // null if no bracket was crossed
    List<BalanceChange> changes,
    boolean replayed
) {
    public BalanceAdjustmentResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public BalanceAdjustmentResult(int newCoins, int newGems, int newExperiencePoints, TitleChangeResult titleChange) {
        this(newCoins, newGems, newExperiencePoints, titleChange, List.of(), false);
    }

    /** The change made to {@code currency}, or null if the request didn't touch it. */
    public BalanceChange changeFor(BalanceCurrency currency) {
        return changes.stream().filter(c -> c.currency() == currency).findFirst().orElse(null);
    }

    /** The balance of {@code currency} after the request. */
    public int balanceOf(BalanceCurrency currency) {
        return switch (currency) {
            case COINS -> newCoins;
            case GEMS -> newGems;
            case EXPERIENCE -> newExperiencePoints;
        };
    }
}
