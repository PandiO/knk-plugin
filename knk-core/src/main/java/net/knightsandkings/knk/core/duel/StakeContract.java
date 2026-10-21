package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * What one offer proposes: a complete contract (DESIGN §6.2). Immutable; a counter is a new contract.
 *
 * @param fixedKitId the kit for {@link GearMode#FIXED_KIT}; null for every other mode
 */
public record StakeContract(GearMode gearMode, Integer fixedKitId, Stake challenger, Stake opponent) {
    public StakeContract {
        Objects.requireNonNull(gearMode, "gearMode");
        challenger = challenger == null ? Stake.NONE : challenger;
        opponent = opponent == null ? Stake.NONE : opponent;
    }

    /** {@code /duel <player> quick}: no stakes, the arena's default gear mode (DESIGN §6.1). */
    public static StakeContract quick(DuelArenaSettings settings) {
        return new StakeContract(settings.defaultGearMode(),
                settings.defaultGearMode() == GearMode.FIXED_KIT ? settings.defaultKitId() : null,
                Stake.NONE, Stake.NONE);
    }

    public Stake stakeOf(DuelSide side) {
        return side == DuelSide.CHALLENGER ? challenger : opponent;
    }

    public boolean hasStakes() {
        return !challenger.isEmpty() || !opponent.isEmpty();
    }

    /**
     * A decisive result moves value: coins or items are staked, or the loser's inventory drops (D14,
     * §10.3). Eligibility and the abuse limits apply only to these.
     */
    public boolean isValueMoving() {
        return hasStakes() || gearMode.dropsInventory();
    }

    /**
     * A friendly duel: no escrow, no fee, no eligibility check (DESIGN §6.2). Only {@code OWN_GEAR_DROP}
     * moves value without a stake, so an empty kit-mode duel is friendly too.
     */
    public boolean isFriendly() {
        return !isValueMoving();
    }

    public long potCoins() {
        return challenger.coins() + opponent.coins();
    }
}
