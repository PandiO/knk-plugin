package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTOs for knk-web-api's {@code GET /api/Arenas} (arena duels KNG-210). Mirrors {@code Dtos/ArenaDtos.cs}. Boxed
 * types throughout, so a missing field maps to {@link net.knightsandkings.knk.api.mapper.ArenaMapper}'s default (the
 * design default) instead of failing the whole payload; the gear mode arrives as a string.
 */
public final class ArenaDtos {
    private ArenaDtos() {}

    public record ArenaDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("districtId") Integer districtId,
            @JsonProperty("location") LocationDto location,
            @JsonProperty("lobbyLocation") LocationDto lobbyLocation,
            @JsonProperty("exitLocation") LocationDto exitLocation,
            @JsonProperty("isDuelEnabled") Boolean isDuelEnabled,
            @JsonProperty("maxQueuedMatches") Integer maxQueuedMatches,
            @JsonProperty("maxOffers") Integer maxOffers,
            @JsonProperty("offerSeconds") Integer offerSeconds,
            @JsonProperty("allowCoinStakes") Boolean allowCoinStakes,
            @JsonProperty("allowItemStakes") Boolean allowItemStakes,
            @JsonProperty("maxCoinStake") Integer maxCoinStake,
            @JsonProperty("maxItemStacks") Integer maxItemStacks,
            @JsonProperty("minStakeRatioPercent") Integer minStakeRatioPercent,
            @JsonProperty("feePercent") Integer feePercent,
            @JsonProperty("stakeMinAccountAgeHours") Integer stakeMinAccountAgeHours,
            @JsonProperty("stakeMinTitleBracketId") Integer stakeMinTitleBracketId,
            @JsonProperty("maxStakedDuelsPerPairPerDay") Integer maxStakedDuelsPerPairPerDay,
            @JsonProperty("pairCooldownMinutes") Integer pairCooldownMinutes,
            @JsonProperty("dailyCoinWinCap") Integer dailyCoinWinCap,
            @JsonProperty("allowOwnGearKeep") Boolean allowOwnGearKeep,
            @JsonProperty("allowOwnGearDrop") Boolean allowOwnGearDrop,
            @JsonProperty("allowFixedKit") Boolean allowFixedKit,
            @JsonProperty("allowKitChoice") Boolean allowKitChoice,
            @JsonProperty("defaultGearMode") String defaultGearMode,
            @JsonProperty("warmupSeconds") Integer warmupSeconds,
            @JsonProperty("countdownSeconds") Integer countdownSeconds,
            @JsonProperty("fightTimeLimitSeconds") Integer fightTimeLimitSeconds,
            @JsonProperty("maxWaitSeconds") Integer maxWaitSeconds,
            @JsonProperty("winnerLingerSeconds") Integer winnerLingerSeconds,
            @JsonProperty("announceMatches") Boolean announceMatches,
            @JsonProperty("announceFriendly") Boolean announceFriendly,
            @JsonProperty("eligibleKits") List<ArenaKitDto> eligibleKits,
            @JsonProperty("battlegrounds") List<BattlegroundDto> battlegrounds,
            @JsonProperty("spectatorAreas") List<SpectatorAreaDto> spectatorAreas
    ) {}

    public record ArenaKitDto(
            @JsonProperty("kitId") Integer kitId,
            @JsonProperty("kitName") String kitName,
            @JsonProperty("sequenceNumber") Integer sequenceNumber
    ) {}

    public record BattlegroundDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("arenaId") Integer arenaId,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("isEnabled") Boolean isEnabled,
            @JsonProperty("allowEntry") Boolean allowEntry,
            @JsonProperty("spawnALocation") LocationDto spawnALocation,
            @JsonProperty("spawnBLocation") LocationDto spawnBLocation
    ) {}

    public record SpectatorAreaDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("arenaId") Integer arenaId,
            @JsonProperty("name") String name,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("teleportEnabled") Boolean teleportEnabled,
            @JsonProperty("location") LocationDto location
    ) {}
}
