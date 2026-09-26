package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.knightsandkings.knk.api.dto.ClanDtos.BannerDesignDto;
import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wire DTOs for knk-web-api's siege runtime-config and readiness payloads (Siege Phase 4).
 * Mirrors {@code Dtos/SiegeDtos.cs} ({@code SiegeRuntimeConfigDto} and its nested types,
 * {@code SiegeConfigurationDto}, {@code SiegeScenarioReadinessDto}). JSON is camelCase; enums arrive
 * as strings ({@code "Defender"}, {@code "Continuous"}, {@code "OPEN"}, {@code "PreLockdownView"})
 * and are parsed in {@link net.knightsandkings.knk.api.mapper.SiegeMapper}. Boxed types throughout,
 * so a missing field maps to the mapper's default instead of failing the whole payload.
 */
public final class SiegeDtos {
    private SiegeDtos() {}

    public record RuntimeConfigDto(
            @JsonProperty("generatedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime generatedAt,
            @JsonProperty("configuration") ConfigurationDto configuration,
            @JsonProperty("lobbies") List<RuntimeLobbyDto> lobbies
    ) {}

    public record ConfigurationDto(
            @JsonProperty("captureAttackBase") Integer captureAttackBase,
            @JsonProperty("captureAttackPerExtra") Integer captureAttackPerExtra,
            @JsonProperty("captureAttackPerExtraInstantVictory") Integer captureAttackPerExtraInstantVictory,
            @JsonProperty("captureDefendBase") Integer captureDefendBase,
            @JsonProperty("captureDefendPerExtra") Integer captureDefendPerExtra,
            @JsonProperty("captureDefendPerExtraInstantVictory") Integer captureDefendPerExtraInstantVictory,
            @JsonProperty("sideCaptureReduction") Double sideCaptureReduction,
            @JsonProperty("voteCloseSecondsBeforeStart") Integer voteCloseSecondsBeforeStart,
            @JsonProperty("drawSecondsBeforeStart") Integer drawSecondsBeforeStart,
            @JsonProperty("hubSecondsBeforeStart") Integer hubSecondsBeforeStart,
            @JsonProperty("teamSplitSecondsBeforeStart") Integer teamSplitSecondsBeforeStart,
            @JsonProperty("matchmakingAnnouncementMarks") List<Integer> matchmakingAnnouncementMarks,
            @JsonProperty("killAnnouncementThresholds") List<Integer> killAnnouncementThresholds,
            @JsonProperty("killStreakAnnounceAbove") Integer killStreakAnnounceAbove,
            @JsonProperty("headshotMultiplier") Double headshotMultiplier,
            @JsonProperty("allowedCommands") List<String> allowedCommands,
            @JsonProperty("spawnPickerDelayTicks") Integer spawnPickerDelayTicks,
            @JsonProperty("enchantDropChancePerMille") Integer enchantDropChancePerMille,
            @JsonProperty("allowedEnchantmentKeys") List<String> allowedEnchantmentKeys,
            @JsonProperty("enchantLevelMin") Integer enchantLevelMin,
            @JsonProperty("enchantLevelMax") Integer enchantLevelMax,
            @JsonProperty("maxBooksAlive") Integer maxBooksAlive,
            @JsonProperty("nonMemberGateView") String nonMemberGateView
    ) {}

    public record RuntimeLobbyDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("key") String key,
            @JsonProperty("mode") String mode,
            @JsonProperty("matchmakingSeconds") Integer matchmakingSeconds,
            @JsonProperty("cooldownSeconds") Integer cooldownSeconds,
            @JsonProperty("voteCandidateCount") Integer voteCandidateCount,
            @JsonProperty("allowRandomVote") Boolean allowRandomVote,
            @JsonProperty("rotation") List<RotationEntryDto> rotation,
            @JsonProperty("skippedScenarios") List<SkippedScenarioDto> skippedScenarios
    ) {}

    public record RotationEntryDto(
            @JsonProperty("weight") Integer weight,
            @JsonProperty("scenario") RuntimeScenarioDto scenario
    ) {}

    public record SkippedScenarioDto(
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("name") String name,
            @JsonProperty("errors") List<ReadinessIssueDto> errors
    ) {}

    public record RuntimeScenarioDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("townId") Integer townId,
            @JsonProperty("townName") String townName,
            @JsonProperty("townWgRegionId") String townWgRegionId,
            @JsonProperty("districts") List<RuntimeDistrictDto> districts,
            @JsonProperty("hubLocation") LocationDto hubLocation,
            @JsonProperty("playersMin") Integer playersMin,
            @JsonProperty("playersMax") Integer playersMax,
            @JsonProperty("minTitleBracketId") Integer minTitleBracketId,
            @JsonProperty("minTitleExperience") Integer minTitleExperience,
            @JsonProperty("matchDurationMinSeconds") Integer matchDurationMinSeconds,
            @JsonProperty("matchDurationPerPlayerSeconds") Integer matchDurationPerPlayerSeconds,
            @JsonProperty("matchDurationMaxSeconds") Integer matchDurationMaxSeconds,
            @JsonProperty("coinRewardWin") Integer coinRewardWin,
            @JsonProperty("expRewardWin") Integer expRewardWin,
            @JsonProperty("gemRewardWin") Integer gemRewardWin,
            @JsonProperty("coinRewardHolding") Integer coinRewardHolding,
            @JsonProperty("expRewardHolding") Integer expRewardHolding,
            @JsonProperty("coinRewardCapture") Integer coinRewardCapture,
            @JsonProperty("expRewardCapture") Integer expRewardCapture,
            @JsonProperty("lockdownScenarioArea") Boolean lockdownScenarioArea,
            @JsonProperty("allowRecapture") Boolean allowRecapture,
            @JsonProperty("enchantDropsEnabled") Boolean enchantDropsEnabled,
            @JsonProperty("teams") List<RuntimeTeamDto> teams,
            @JsonProperty("objectives") List<RuntimeObjectiveDto> objectives,
            @JsonProperty("gates") List<RuntimeGateDto> gates,
            @JsonProperty("areaGateStructureIds") List<Integer> areaGateStructureIds
    ) {}

    public record RuntimeDistrictDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("wgRegionId") String wgRegionId
    ) {}

    public record RuntimeTeamDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("role") String role,
            @JsonProperty("allianceGroup") Integer allianceGroup,
            @JsonProperty("clanId") Integer clanId,
            @JsonProperty("name") String name,
            @JsonProperty("chatColor") String chatColor,
            @JsonProperty("bannerDesign") BannerDesignDto bannerDesign,
            @JsonProperty("startMessage") String startMessage,
            @JsonProperty("spawnpoints") List<RuntimeSpawnpointDto> spawnpoints
    ) {}

    public record RuntimeSpawnpointDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("name") String name,
            @JsonProperty("location") LocationDto location,
            @JsonProperty("safeZoneRadius") Double safeZoneRadius
    ) {}

    public record RuntimeObjectiveDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("name") String name,
            @JsonProperty("captureLocation") LocationDto captureLocation,
            @JsonProperty("gateStructureId") Integer gateStructureId,
            @JsonProperty("capturePoints") Integer capturePoints,
            @JsonProperty("captureRadius") Double captureRadius,
            @JsonProperty("instantVictory") Boolean instantVictory,
            @JsonProperty("initialHolderTeamId") Integer initialHolderTeamId,
            @JsonProperty("spawnWhenHeld") Boolean spawnWhenHeld,
            @JsonProperty("gateStateOnCapture") String gateStateOnCapture
    ) {}

    public record RuntimeGateDto(
            @JsonProperty("gateStructureId") Integer gateStructureId,
            @JsonProperty("name") String name,
            @JsonProperty("initialOwnerTeamId") Integer initialOwnerTeamId,
            @JsonProperty("initialState") String initialState,
            @JsonProperty("damageable") Boolean damageable,
            @JsonProperty("isObjectiveGate") Boolean isObjectiveGate
    ) {}

    public record ReadinessDto(
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("isReady") Boolean isReady,
            @JsonProperty("spatialChecksRun") Boolean spatialChecksRun,
            @JsonProperty("errors") List<ReadinessIssueDto> errors,
            @JsonProperty("warnings") List<ReadinessIssueDto> warnings
    ) {}

    public record ReadinessIssueDto(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("entityType") String entityType,
            @JsonProperty("entityId") Integer entityId
    ) {}
}
