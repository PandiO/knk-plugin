package net.knightsandkings.knk.api.mapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaKitDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.BattlegroundDto;
import net.knightsandkings.knk.api.dto.ArenaDtos.SpectatorAreaDto;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaBattleground;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaSpectatorArea;

/**
 * {@code GET /api/Arenas} → core records (KNG-210). A missing setting takes the design default (DESIGN.md §4.2), the
 * same value the API stores for a new arena; rows without an id are dropped.
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
                LocationMapper.toCore(d.lobbyLocation()),
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
                battlegrounds(d.id(), d.battlegrounds()),
                spectatorAreas(d.id(), d.spectatorAreas()));
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

    private static List<KnkArenaBattleground> battlegrounds(int arenaId, List<BattlegroundDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).filter(b -> b.id() != null)
                .map(b -> new KnkArenaBattleground(b.id(), or(b.arenaId(), arenaId), b.key(), b.name(), b.wgRegionId(),
                        b.worldName(), or(b.sortOrder(), 0), or(b.isEnabled(), true), or(b.allowEntry(), false),
                        LocationMapper.toCore(b.spawnALocation()), LocationMapper.toCore(b.spawnBLocation())))
                .sorted(Comparator.comparingInt(KnkArenaBattleground::sortOrder).thenComparingInt(KnkArenaBattleground::id))
                .toList();
    }

    private static List<KnkArenaSpectatorArea> spectatorAreas(int arenaId, List<SpectatorAreaDto> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(Objects::nonNull).filter(a -> a.id() != null)
                .map(a -> new KnkArenaSpectatorArea(a.id(), or(a.arenaId(), arenaId), a.name(), a.wgRegionId(), a.worldName(),
                        or(a.teleportEnabled(), false), LocationMapper.toCore(a.location())))
                .toList();
    }

    private static int or(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
