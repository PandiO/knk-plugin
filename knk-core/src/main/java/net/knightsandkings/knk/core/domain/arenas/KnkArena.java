package net.knightsandkings.knk.core.domain.arenas;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A duel arena as the API reports it (arena duels KNG-210, knk-workspace docs/specs/arena-duels/DESIGN.md §4.1-4.2):
 * the domain fields the plugin needs, the duel settings, and its battlegrounds, spectator areas and eligible kits.
 * Read through {@code ArenasDataAccess}.
 *
 * @param exitLocation null = the domain's own {@link #location()}
 * @param stakeMinAccountAgeHours null = the /pay rule (CurrencyPolicy)
 * @param stakeMinTitleBracketId null = the /pay minimum title
 * @param fightTimeLimitSeconds 0 = no limit
 * @param dailyCoinWinCap 0 = off
 * @param minStakeRatioPercent 0 = off
 * @param eligibleKits in order; the first is the default pick in {@link DuelGearMode#KIT_CHOICE}
 * @param battlegrounds in allocation order
 */
public record KnkArena(
        int id,
        String key,
        String name,
        String wgRegionId,
        String worldName,
        Integer districtId,
        KnkLocation location,
        KnkLocation lobbyLocation,
        KnkLocation exitLocation,
        boolean duelEnabled,
        int maxQueuedMatches,
        int maxOffers,
        int offerSeconds,
        boolean allowCoinStakes,
        boolean allowItemStakes,
        int maxCoinStake,
        int maxItemStacks,
        int minStakeRatioPercent,
        int feePercent,
        Integer stakeMinAccountAgeHours,
        Integer stakeMinTitleBracketId,
        int maxStakedDuelsPerPairPerDay,
        int pairCooldownMinutes,
        int dailyCoinWinCap,
        boolean allowOwnGearKeep,
        boolean allowOwnGearDrop,
        boolean allowFixedKit,
        boolean allowKitChoice,
        DuelGearMode defaultGearMode,
        int warmupSeconds,
        int countdownSeconds,
        int fightTimeLimitSeconds,
        int maxWaitSeconds,
        int winnerLingerSeconds,
        boolean announceMatches,
        boolean announceFriendly,
        List<KnkArenaKit> eligibleKits,
        List<KnkArenaBattleground> battlegrounds,
        List<KnkArenaSpectatorArea> spectatorAreas
) {
    /** What duelists fight with (DESIGN D9). The API stores the names as strings. */
    public enum DuelGearMode {
        OWN_GEAR_KEEP, OWN_GEAR_DROP, FIXED_KIT, KIT_CHOICE;

        /** Parses the API's name (any case); empty for null or an unknown name. */
        public static Optional<DuelGearMode> parse(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }
    }

    public KnkArena {
        eligibleKits = eligibleKits == null ? List.of() : List.copyOf(eligibleKits);
        battlegrounds = battlegrounds == null ? List.of() : List.copyOf(battlegrounds);
        spectatorAreas = spectatorAreas == null ? List.of() : List.copyOf(spectatorAreas);
        defaultGearMode = defaultGearMode == null ? DuelGearMode.OWN_GEAR_KEEP : defaultGearMode;
    }

    /** Whether a proposal may pick {@code mode} here. */
    public boolean allows(DuelGearMode mode) {
        if (mode == null) {
            return false;
        }
        return switch (mode) {
            case OWN_GEAR_KEEP -> allowOwnGearKeep;
            case OWN_GEAR_DROP -> allowOwnGearDrop;
            case FIXED_KIT -> allowFixedKit;
            case KIT_CHOICE -> allowKitChoice;
        };
    }

    /** Where duelists leave to: the exit location, else the domain's own location (may be null). */
    public KnkLocation exitOrDomainLocation() {
        return exitLocation != null ? exitLocation : location;
    }

    /** The battlegrounds a match may use: enabled and with both spawns, in allocation order. */
    public List<KnkArenaBattleground> usableBattlegrounds() {
        return battlegrounds.stream().filter(KnkArenaBattleground::isUsable).toList();
    }

    /** The first eligible kit (the KIT_CHOICE default), if any. */
    public Optional<KnkArenaKit> defaultKit() {
        return eligibleKits.isEmpty() ? Optional.empty() : Optional.of(eligibleKits.get(0));
    }
}
