package net.knightsandkings.knk.core.duel;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The duel settings of one arena that the pure rules read (DESIGN §4.2; edited in the web app). The
 * runtime maps them from the arena gateway (plan Phase 1); eligibility, the pair/daily limits and the
 * review thresholds are checked by the API (Phase 2) and are not here.
 *
 * @param maxOffers            offers per negotiation, 1–6 (D2)
 * @param offerSeconds         time per turn
 * @param maxCoinStake         per side
 * @param maxItemStacks        per side
 * @param minStakeRatioPercent 0 = off; see {@link StakeContractValidator}
 * @param feePercent           0–20, of the coin pot on a decisive result (D15)
 * @param eligibleKitIds       ordered; the first is the {@code KIT_CHOICE} default (O3)
 * @param fightTimeLimitSeconds 0 = no limit (D11)
 * @param maxWaitSeconds       waiting for a free battleground after warmup, then cancel
 */
public record DuelArenaSettings(
        int arenaId,
        int maxOffers,
        int offerSeconds,
        boolean allowCoinStakes,
        boolean allowItemStakes,
        long maxCoinStake,
        int maxItemStacks,
        int minStakeRatioPercent,
        int feePercent,
        Set<GearMode> allowedGearModes,
        GearMode defaultGearMode,
        List<Integer> eligibleKitIds,
        int warmupSeconds,
        int countdownSeconds,
        int fightTimeLimitSeconds,
        int maxWaitSeconds,
        int winnerLingerSeconds,
        int maxQueuedMatches
) {
    public static final int MIN_OFFERS = 1;
    public static final int MAX_OFFERS = 6;

    public DuelArenaSettings {
        if (maxOffers < MIN_OFFERS || maxOffers > MAX_OFFERS) {
            throw new IllegalArgumentException("maxOffers must be " + MIN_OFFERS + "-" + MAX_OFFERS + ": " + maxOffers);
        }
        if (offerSeconds < 1) throw new IllegalArgumentException("offerSeconds must be >= 1");
        if (maxCoinStake < 0 || maxItemStacks < 0) throw new IllegalArgumentException("stake limits must be >= 0");
        if (minStakeRatioPercent < 0 || minStakeRatioPercent > 100) {
            throw new IllegalArgumentException("minStakeRatioPercent must be 0-100");
        }
        if (feePercent < 0 || feePercent > 20) throw new IllegalArgumentException("feePercent must be 0-20");
        Objects.requireNonNull(allowedGearModes, "allowedGearModes");
        if (allowedGearModes.isEmpty()) throw new IllegalArgumentException("at least one gear mode must be allowed");
        allowedGearModes = Set.copyOf(EnumSet.copyOf(allowedGearModes));
        Objects.requireNonNull(defaultGearMode, "defaultGearMode");
        if (!allowedGearModes.contains(defaultGearMode)) {
            throw new IllegalArgumentException("defaultGearMode " + defaultGearMode + " is not allowed");
        }
        eligibleKitIds = List.copyOf(eligibleKitIds);
        if (warmupSeconds < 0 || countdownSeconds < 0 || fightTimeLimitSeconds < 0 || maxWaitSeconds < 0
                || winnerLingerSeconds < 0 || maxQueuedMatches < 0) {
            throw new IllegalArgumentException("timings and the queue cap must be >= 0");
        }
    }

    /** DESIGN §4.2 defaults: own gear (keep) only, no kits. */
    public static DuelArenaSettings defaults(int arenaId) {
        return new DuelArenaSettings(arenaId, 3, 60, true, true, 10_000, 9, 50, 5,
                Set.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, List.of(),
                30, 10, 300, 180, 5, 3);
    }

    public boolean allows(GearMode mode) {
        return allowedGearModes.contains(mode);
    }

    public boolean isKitEligible(Integer kitId) {
        return kitId != null && eligibleKitIds.contains(kitId);
    }

    /** The {@code KIT_CHOICE} placeholder and default pick (O3), or null when there is no eligible kit. */
    public Integer defaultKitId() {
        return eligibleKitIds.isEmpty() ? null : eligibleKitIds.getFirst();
    }

    /** {@link #feePercent} of {@code potCoins}, rounded down (D15). */
    public long feeFor(long potCoins) {
        return potCoins <= 0 ? 0 : potCoins * feePercent / 100;
    }

    public DuelTimings timings(int agreedDrawWindowSeconds) {
        return new DuelTimings(warmupSeconds, countdownSeconds, fightTimeLimitSeconds, winnerLingerSeconds,
                agreedDrawWindowSeconds);
    }
}
