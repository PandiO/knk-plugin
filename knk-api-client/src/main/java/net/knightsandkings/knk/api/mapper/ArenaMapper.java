package net.knightsandkings.knk.api.mapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.api.dto.ArenaDtos.AreaDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.AreaRegionDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaKitDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.SubRegionDto;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;

/**
 * {@code GET /api/Arenas} → core records (KNG-210; areas rev. 6). A missing setting takes the design default (DESIGN.md
 * §4.2), the same value the API stores for a new arena; rows without an id, and areas of an unknown kind, are dropped.
 */
public final class ArenaMapper {
    private ArenaMapper() {}

    public static List<KnkArena> toCore(List<ArenaDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream().filter(Objects::nonNull).filter(d -> d.id() != null).map(ArenaMapper::toCore).toList();
    }

    public static KnkArena toCore(ArenaDto d) {
        return new KnkArena(
                d.id(),
                d.key(),
                d.name(),
                d.wgRegionId(),
                d.worldName(),
                d.districtId(),
                LocationMapper.toCore(d.location()),
                LocationMapper.toCore(d.exitLocation()),
                or(d.isDuelEnabled(), true),
                or(d.maxQueuedMatches(), 3),
                or(d.maxOffers(), 3),
                or(d.offerSeconds(), 60),
                or(d.allowCoinStakes(), true),
                or(d.allowItemStakes(), true),
                or(d.maxCoinStake(), 10_000),
                or(d.maxItemStacks(), 9),
                or(d.minStakeRatioPercent(), 50),
                or(d.feePercent(), 5),
                d.stakeMinAccountAgeHours(),
                d.stakeMinTitleBracketId(),
                or(d.maxStakedDuelsPerPairPerDay(), 3),
                or(d.pairCooldownMinutes(), 10),
                or(d.dailyCoinWinCap(), 25_000),
                or(d.allowOwnGearKeep(), true),
                or(d.allowOwnGearDrop(), false),
                or(d.allowFixedKit(), false),
                or(d.allowKitChoice(), false),
                KnkArena.DuelGearMode.parse(d.defaultGearMode()).orElse(KnkArena.DuelGearMode.OWN_GEAR_KEEP),
                or(d.warmupSeconds(), 30),
                or(d.countdownSeconds(), 10),
                or(d.fightTimeLimitSeconds(), 300),
                or(d.maxWaitSeconds(), 180),
                or(d.winnerLingerSeconds(), 5),
                or(d.announceMatches(), true),
                or(d.announceFriendly(), true),
                kits(d.eligibleKits()),
                areas(d.id(), d.areas()),
                subRegionIds(d.subRegions()));
    }

    private static List<KnkArenaKit> kits(List<ArenaKitDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).filter(k -> k.kitId() != null)
                .map(k -> new KnkArenaKit(k.kitId(), k.kitName(), or(k.sequenceNumber(), 0)))
                .sorted(Comparator.comparingInt(KnkArenaKit::sequenceNumber))
                .toList();
    }

    private static List<KnkArenaArea> areas(int arenaId, List<AreaDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).filter(a -> a.id() != null)
                .filter(a -> KnkArenaArea.Kind.parse(a.kind()).isPresent())
                .map(a -> new KnkArenaArea(a.id(), or(a.arenaId(), arenaId), KnkArenaArea.Kind.parse(a.kind()).orElseThrow(),
                        a.key(), a.name(), or(a.sortOrder(), 0), or(a.isEnabled(), false),
                        LocationMapper.toCore(a.spawnALocation()), LocationMapper.toCore(a.spawnBLocation()),
                        LocationMapper.toCore(a.arrivalLocation()), a.teleportMinTitleBracketId(), a.teleportPriceGems(),
                        regions(a.regions())))
                .sorted(KnkArenaArea.ORDER)
                .toList();
    }

    private static List<KnkArenaAreaRegion> regions(List<AreaRegionDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).filter(r -> r.id() != null && r.wgRegionId() != null && !r.wgRegionId().isBlank())
                .map(r -> new KnkArenaAreaRegion(r.id(), r.wgRegionId(), r.worldName()))
                .toList();
    }

    private static List<String> subRegionIds(List<SubRegionDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).map(SubRegionDto::wgRegionId)
                .filter(id -> id != null && !id.isBlank()).toList();
    }

    private static int or(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
