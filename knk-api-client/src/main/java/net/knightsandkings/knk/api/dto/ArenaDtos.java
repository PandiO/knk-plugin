package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire DTOs for knk-web-api's {@code GET /api/Arenas} (arena duels KNG-210; areas rev. 6). Mirrors {@code Dtos/ArenaDtos.cs}. Boxed
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
            @JsonProperty("areas") List<AreaDto> areas,
            @JsonProperty("subRegions") List<SubRegionDto> subRegions
    ) {}

    public record ArenaKitDto(
            @JsonProperty("kitId") Integer kitId,
            @JsonProperty("kitName") String kitName,
            @JsonProperty("sequenceNumber") Integer sequenceNumber
    ) {}

    /** A battleground, spectator area or lobby (rev. 6), with its regions. */
    public record AreaDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("arenaId") Integer arenaId,
            @JsonProperty("kind") String kind,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("isEnabled") Boolean isEnabled,
            @JsonProperty("spawnALocation") LocationDto spawnALocation,
            @JsonProperty("spawnBLocation") LocationDto spawnBLocation,
            @JsonProperty("arrivalLocation") LocationDto arrivalLocation,
            @JsonProperty("teleportMinTitleBracketId") Integer teleportMinTitleBracketId,
            @JsonProperty("teleportPriceGems") Integer teleportPriceGems,
            @JsonProperty("regions") List<AreaRegionDto> regions
    ) {}

    public record AreaRegionDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("worldName") String worldName
    ) {}

    /** KNG-254: one of the arena domain's own sub-regions. */
    public record SubRegionDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("wgRegionId") String wgRegionId
    ) {}
}
