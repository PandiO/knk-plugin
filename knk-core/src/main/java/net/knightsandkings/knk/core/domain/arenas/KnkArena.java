package net.knightsandkings.knk.core.domain.arenas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A duel arena as the API reports it (arena duels KNG-210, knk-workspace docs/specs/arena-duels/DESIGN.md §4.1-4.2):
 * the domain fields the plugin needs (with its KNG-253 sub-regions), the duel settings, its eligible kits and its
 * areas (rev. 6: battlegrounds, spectator areas and the lobby, each with its own regions; not domains).
 * Read through {@code ArenasDataAccess}.
 *
 * @param exitLocation null = the domain's own {@link #location()}
 * @param stakeMinAccountAgeHours null = the /pay rule (CurrencyPolicy)
 * @param stakeMinTitleBracketId null = the /pay minimum title
 * @param fightTimeLimitSeconds 0 = no limit
 * @param dailyCoinWinCap 0 = off
 * @param minStakeRatioPercent 0 = off
 * @param eligibleKits in order; the first is the default pick in {@link DuelGearMode#KIT_CHOICE}
 * @param areas        every area, any kind and state; see {@link #battlegrounds()}, {@link #spectatorAreas()}, {@link #lobby()}
 * @param subRegionIds the arena domain's own sub-regions (KNG-253): the arena is their union with {@link #wgRegionId()}
 */
public record KnkArena(
        int id,
        String key,
        String name,
        String wgRegionId,
        String worldName,
        Integer districtId,
        KnkLocation location,
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
        List<KnkArenaArea> areas,
        List<String> subRegionIds
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
        areas = areas == null ? List.of() : areas.stream().sorted(KnkArenaArea.ORDER).toList();
        subRegionIds = subRegionIds == null ? List.of()
                : subRegionIds.stream().filter(region -> region != null && !region.isBlank()).toList();
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

    /** Every battleground area (enabled or not), in allocation order. */
    public List<KnkArenaArea> battlegrounds() {
        return ofKind(KnkArenaArea.Kind.BATTLEGROUND, false);
    }

    /** The battlegrounds a match may use: enabled, both spawns and a region, in allocation order. */
    public List<KnkArenaArea> usableBattlegrounds() {
        return battlegrounds().stream().filter(KnkArenaArea::isUsableBattleground).toList();
    }

    /** The enabled spectator areas, in order (the first with an arrival is where [Spectate] goes). */
    public List<KnkArenaArea> spectatorAreas() {
        return ofKind(KnkArenaArea.Kind.SPECTATOR, true);
    }

    /** The enabled lobby, if any (an arena open for duels has one with an arrival location). */
    public Optional<KnkArenaArea> lobby() {
        return ofKind(KnkArenaArea.Kind.LOBBY, true).stream().findFirst();
    }

    /** Where duelists arrive for the warmup: the lobby area's arrival location (null without one). */
    public KnkLocation lobbyLocation() {
        return lobby().map(KnkArenaArea::arrival).orElse(null);
    }

    /** The first enabled spectator area with an arrival location: the spectate teleport's target. */
    public Optional<KnkArenaArea> spectateTarget() {
        return spectatorAreas().stream().filter(area -> area.arrival() != null).findFirst();
    }

    /** The area with that id, any kind. */
    public Optional<KnkArenaArea> area(int areaId) {
        return areas.stream().filter(area -> area.id() == areaId).findFirst();
    }

    /** The arena domain's own regions: its main region, then its sub-regions (KNG-253). */
    public List<String> ownRegionIds() {
        List<String> ids = new ArrayList<>();
        if (wgRegionId != null && !wgRegionId.isBlank()) {
            ids.add(wgRegionId);
        }
        ids.addAll(subRegionIds);
        return List.copyOf(ids);
    }

    /** Whether {@code regionId} is the arena's main region or one of its sub-regions (ids ignore case). */
    public boolean isOwnRegion(String regionId) {
        return regionId != null && ownRegionIds().stream().anyMatch(id -> id.equalsIgnoreCase(regionId.trim()));
    }

    private List<KnkArenaArea> ofKind(KnkArenaArea.Kind kind, boolean enabledOnly) {
        return areas.stream().filter(area -> area.kind() == kind && (!enabledOnly || area.enabled())).toList();
    }

    /** The first eligible kit (the KIT_CHOICE default), if any. */
    public Optional<KnkArenaKit> defaultKit() {
        return eligibleKits.isEmpty() ? Optional.empty() : Optional.of(eligibleKits.get(0));
    }
}
