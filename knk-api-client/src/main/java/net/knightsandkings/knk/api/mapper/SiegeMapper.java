package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.SiegeDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessIssueDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RotationEntryDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeConfigDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeDistrictDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeGateDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeLobbyDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeObjectiveDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeScenarioDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeSpawnpointDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.RuntimeTeamDto;
import net.knightsandkings.knk.api.dto.SiegeDtos.SkippedScenarioDto;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadinessIssue;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRewards;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRotationEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSkippedScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeLobbyMode;
import net.knightsandkings.knk.core.domain.siege.SiegeNonMemberGateView;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Siege runtime-config / readiness DTOs to the knk-core records (Siege Phase 4). Missing numbers
 * fall back to the server-side defaults ({@link KnkSiegeConfiguration#legacyDefaults()}, the
 * scenario defaults from DESIGN §3.3), missing lists to empty. Nothing server-resolved (team
 * identity, first-Defender holders/owners, capture points, {@code isObjectiveGate}) is re-derived.
 */
public final class SiegeMapper {
    private SiegeMapper() {}

    public static KnkSiegeRuntimeConfig toCore(RuntimeConfigDto dto) {
        if (dto == null) return null;
        return new KnkSiegeRuntimeConfig(
                dto.generatedAt() != null ? dto.generatedAt().toInstant() : null,
                toCore(dto.configuration()),
                mapList(dto.lobbies(), SiegeMapper::toCore)
        );
    }

    public static KnkSiegeConfiguration toCore(ConfigurationDto dto) {
        KnkSiegeConfiguration d = KnkSiegeConfiguration.legacyDefaults();
        if (dto == null) return d;
        return new KnkSiegeConfiguration(
                or(dto.captureAttackBase(), d.captureAttackBase()),
                or(dto.captureAttackPerExtra(), d.captureAttackPerExtra()),
                or(dto.captureAttackPerExtraInstantVictory(), d.captureAttackPerExtraInstantVictory()),
                or(dto.captureDefendBase(), d.captureDefendBase()),
                or(dto.captureDefendPerExtra(), d.captureDefendPerExtra()),
                or(dto.captureDefendPerExtraInstantVictory(), d.captureDefendPerExtraInstantVictory()),
                or(dto.sideCaptureReduction(), d.sideCaptureReduction()),
                or(dto.voteCloseSecondsBeforeStart(), d.voteCloseSecondsBeforeStart()),
                or(dto.drawSecondsBeforeStart(), d.drawSecondsBeforeStart()),
                or(dto.hubSecondsBeforeStart(), d.hubSecondsBeforeStart()),
                or(dto.teamSplitSecondsBeforeStart(), d.teamSplitSecondsBeforeStart()),
                dto.matchmakingAnnouncementMarks() != null ? dto.matchmakingAnnouncementMarks() : d.matchmakingAnnouncementMarks(),
                dto.killAnnouncementThresholds() != null ? dto.killAnnouncementThresholds() : d.killAnnouncementThresholds(),
                or(dto.killStreakAnnounceAbove(), d.killStreakAnnounceAbove()),
                or(dto.headshotMultiplier(), d.headshotMultiplier()),
                dto.allowedCommands() != null ? dto.allowedCommands() : d.allowedCommands(),
                or(dto.spawnPickerDelayTicks(), d.spawnPickerDelayTicks()),
                or(dto.enchantDropChancePerMille(), d.enchantDropChancePerMille()),
                dto.allowedEnchantmentKeys(),
                or(dto.enchantLevelMin(), d.enchantLevelMin()),
                or(dto.enchantLevelMax(), d.enchantLevelMax()),
                or(dto.maxBooksAlive(), d.maxBooksAlive()),
                SiegeNonMemberGateView.fromApi(dto.nonMemberGateView())
        );
    }

    public static KnkSiegeLobby toCore(RuntimeLobbyDto dto) {
        return new KnkSiegeLobby(
                or(dto.id(), 0),
                dto.name(),
                dto.key(),
                SiegeLobbyMode.fromApi(dto.mode()),
                or(dto.matchmakingSeconds(), 300),
                or(dto.cooldownSeconds(), 900),
                or(dto.voteCandidateCount(), 2),
                or(dto.allowRandomVote(), true),
                mapList(dto.rotation(), SiegeMapper::toCore),
                mapList(dto.skippedScenarios(), SiegeMapper::toCore)
        );
    }

    static KnkSiegeRotationEntry toCore(RotationEntryDto dto) {
        // A rotation entry without its scenario is useless to the runtime; drop it.
        if (dto.scenario() == null) return null;
        return new KnkSiegeRotationEntry(or(dto.weight(), 1), toCore(dto.scenario()));
    }

    static KnkSiegeSkippedScenario toCore(SkippedScenarioDto dto) {
        return new KnkSiegeSkippedScenario(or(dto.siegeScenarioId(), 0), dto.name(), mapList(dto.errors(), SiegeMapper::toCore));
    }

    public static KnkSiegeScenario toCore(RuntimeScenarioDto dto) {
        if (dto == null) return null;
        KnkSiegeMatchLength length = KnkSiegeMatchLength.DEFAULT;
        return new KnkSiegeScenario(
                or(dto.id(), 0),
                dto.name(),
                dto.description(),
                or(dto.townId(), 0),
                dto.townName(),
                dto.townWgRegionId(),
                mapList(dto.districts(), SiegeMapper::toCore),
                LocationMapper.toCore(dto.hubLocation()),
                or(dto.playersMin(), 2),
                or(dto.playersMax(), 50),
                dto.minTitleBracketId(),
                dto.minTitleExperience(),
                new KnkSiegeMatchLength(
                        or(dto.matchDurationMinSeconds(), length.minSeconds()),
                        or(dto.matchDurationPerPlayerSeconds(), length.perPlayerSeconds()),
                        or(dto.matchDurationMaxSeconds(), length.maxSeconds())),
                new KnkSiegeRewards(
                        or(dto.coinRewardWin(), 0),
                        or(dto.expRewardWin(), 0),
                        or(dto.gemRewardWin(), 0),
                        or(dto.coinRewardHolding(), 0),
                        or(dto.expRewardHolding(), 0),
                        or(dto.coinRewardCapture(), 0),
                        or(dto.expRewardCapture(), 0)),
                or(dto.lockdownScenarioArea(), true),
                or(dto.allowRecapture(), false),
                or(dto.enchantDropsEnabled(), true),
                mapList(dto.teams(), SiegeMapper::toCore),
                mapList(dto.objectives(), SiegeMapper::toCore),
                mapList(dto.gates(), SiegeMapper::toCore),
                dto.areaGateStructureIds()
        );
    }

    static KnkSiegeDistrict toCore(RuntimeDistrictDto dto) {
        return new KnkSiegeDistrict(or(dto.id(), 0), dto.name(), dto.wgRegionId());
    }

    static KnkSiegeTeam toCore(RuntimeTeamDto dto) {
        return new KnkSiegeTeam(
                or(dto.id(), 0),
                or(dto.sortOrder(), 0),
                SiegeTeamRole.fromApi(dto.role()),
                or(dto.allianceGroup(), 0),
                dto.clanId(),
                dto.name(),
                dto.chatColor(),
                ClanMapper.toCore(dto.bannerDesign()),
                dto.startMessage(),
                mapList(dto.spawnpoints(), SiegeMapper::toCore)
        );
    }

    static KnkSiegeSpawnpoint toCore(RuntimeSpawnpointDto dto) {
        return new KnkSiegeSpawnpoint(
                or(dto.id(), 0),
                or(dto.sortOrder(), 0),
                dto.name(),
                LocationMapper.toCore(dto.location()),
                or(dto.safeZoneRadius(), 4.0)
        );
    }

    static KnkSiegeObjective toCore(RuntimeObjectiveDto dto) {
        return new KnkSiegeObjective(
                or(dto.id(), 0),
                or(dto.sortOrder(), 0),
                dto.name(),
                LocationMapper.toCore(dto.captureLocation()),
                dto.gateStructureId(),
                or(dto.capturePoints(), 500),
                or(dto.captureRadius(), 2.5),
                or(dto.instantVictory(), false),
                or(dto.initialHolderTeamId(), 0),
                or(dto.spawnWhenHeld(), true),
                SiegeGateState.fromApi(dto.gateStateOnCapture(), SiegeGateState.OPEN)
        );
    }

    static KnkSiegeGate toCore(RuntimeGateDto dto) {
        return new KnkSiegeGate(
                or(dto.gateStructureId(), 0),
                dto.name(),
                or(dto.initialOwnerTeamId(), 0),
                SiegeGateState.fromApi(dto.initialState(), SiegeGateState.CLOSED),
                or(dto.damageable(), true),
                or(dto.isObjectiveGate(), false)
        );
    }

    public static KnkSiegeReadiness toCore(ReadinessDto dto) {
        if (dto == null) return null;
        return new KnkSiegeReadiness(
                or(dto.siegeScenarioId(), 0),
                or(dto.isReady(), false),
                or(dto.spatialChecksRun(), false),
                mapList(dto.errors(), SiegeMapper::toCore),
                mapList(dto.warnings(), SiegeMapper::toCore)
        );
    }

    static KnkSiegeReadinessIssue toCore(ReadinessIssueDto dto) {
        return new KnkSiegeReadinessIssue(dto.code(), dto.message(), dto.entityType(), dto.entityId());
    }

    private static <D, C> List<C> mapList(List<D> dtos, Function<D, C> mapper) {
        if (dtos == null) return List.of();
        return dtos.stream().filter(Objects::nonNull).map(mapper).filter(Objects::nonNull).toList();
    }

    private static int or(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static double or(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
