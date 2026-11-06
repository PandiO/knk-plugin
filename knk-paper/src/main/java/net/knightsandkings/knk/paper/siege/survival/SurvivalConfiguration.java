package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.economy.KillRewardSettings;
import net.knightsandkings.knk.core.survival.end.EndConditionSettings;
import net.knightsandkings.knk.core.survival.scaling.WaveScalingSettings;
import net.knightsandkings.knk.core.survival.spawn.MonsterMix;
import net.knightsandkings.knk.core.survival.spawn.SpawnZone;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveProfile;
import net.knightsandkings.knk.core.survival.spawn.SpecialWaveSchedule;
import net.knightsandkings.knk.core.survival.stuck.StuckSettings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The frozen survival configuration of one match (DESIGN §3 step 2, §7 "snapshot config at match start"): the API's
 * {@code ConfigurationDto} mapped onto the core records, with every default resolved. Immutable; built once by
 * {@link SurvivalConfigurationMapper} and kept for the match.
 *
 * @param lobbyId the lobby the configuration was resolved for (its all-captured override), empty = scenario only
 */
public record SurvivalConfiguration(
        int scenarioId,
        String scenarioName,
        OptionalInt lobbyId,
        boolean enabled,
        int minPlayers,
        int humanTeamId,
        int npcTeamId,
        boolean allowRecapture,
        long intermissionMillis,
        EndConditionSettings endConditions,
        List<ObjectiveSpec> objectives,
        List<GateSpec> gates,
        StuckSettings stuck,
        DifficultySpec difficulty,
        MonsterMix monsterMix,
        Map<String, SpecialWaveProfile> specialProfiles,
        SpecialWaveSchedule specialSchedule,
        List<ZoneSpec> zones,
        int favorNearPlayersPercent,
        int nearZoneCount,
        KillRewardSettings killRewards,
        Readiness readiness,
        String generatedAt
) {

    public SurvivalConfiguration {
        Objects.requireNonNull(endConditions, "endConditions");
        Objects.requireNonNull(stuck, "stuck");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(monsterMix, "monsterMix");
        Objects.requireNonNull(readiness, "readiness");
        scenarioName = scenarioName == null ? "" : scenarioName;
        lobbyId = lobbyId == null ? OptionalInt.empty() : lobbyId;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        gates = gates == null ? List.of() : List.copyOf(gates);
        specialProfiles = specialProfiles == null ? Map.of() : Map.copyOf(specialProfiles);
        specialSchedule = specialSchedule == null ? SpecialWaveSchedule.none() : specialSchedule;
        zones = zones == null ? List.of() : List.copyOf(zones);
        killRewards = killRewards == null ? KillRewardSettings.defaults() : killRewards;
        generatedAt = generatedAt == null ? "" : generatedAt;
        if (minPlayers < 1) minPlayers = 1;
        if (intermissionMillis < 0) intermissionMillis = 0;
    }

    /** An objective of the scenario as survival sees it. */
    public record ObjectiveSpec(int id, String name, boolean instaLose, OptionalInt gateStructureId,
                                Optional<WorldPoint> capture, TargetOverrideMode override) {
        public ObjectiveSpec {
            name = name == null ? ("objective " + id) : name;
            gateStructureId = gateStructureId == null ? OptionalInt.empty() : gateStructureId;
            capture = capture == null ? Optional.empty() : capture;
            override = override == null ? TargetOverrideMode.STANDARD : override;
        }

        public String targetId() {
            return "objective:" + id;
        }
    }

    /** A gate structure of the scenario. */
    public record GateSpec(int gateStructureId, String name, boolean objectiveGate, TargetOverrideMode override) {
        public GateSpec {
            name = name == null ? ("gate " + gateStructureId) : name;
            override = override == null ? TargetOverrideMode.STANDARD : override;
        }

        public String targetId() {
            return "gate:" + gateStructureId;
        }
    }

    /** The chosen difficulty profile. */
    public record DifficultySpec(int id, String name, WaveScalingSettings scaling, double repairRatePerSecond,
                                 double gateDamageFactor, double captureContributionWeight, long spawnIntervalMillis,
                                 OptionalDouble aggroRadius, OptionalDouble leashDistance) {
        public DifficultySpec {
            Objects.requireNonNull(scaling, "scaling");
            name = name == null ? ("profile " + id) : name;
            aggroRadius = aggroRadius == null ? OptionalDouble.empty() : aggroRadius;
            leashDistance = leashDistance == null ? OptionalDouble.empty() : leashDistance;
            if (!(gateDamageFactor >= 0) || !Double.isFinite(gateDamageFactor)) gateDamageFactor = 1.0;
            if (!(captureContributionWeight >= 0) || !Double.isFinite(captureContributionWeight)) captureContributionWeight = 1.0;
            if (spawnIntervalMillis < 0) spawnIntervalMillis = 0;
        }
    }

    /** A point in a world (the DTO's location with its world name). */
    public record WorldPoint(String world, double x, double y, double z, float yaw) {
        public WorldPoint {
            world = world == null ? "" : world;
        }

        public SurvivalPoint point() {
            return new SurvivalPoint(x, y, z);
        }
    }

    /**
     * A spawnzone: the core {@link SpawnZone} (id, weight, prerequisites, anchor) plus what the spawner needs.
     *
     * @param spawnPoints the zone's {@code Spawn} points in order; empty = spawn within {@code areaRadius} of the anchor
     * @param lanePoints  the zone's ordered {@code Lane} points (the optional lane override, DESIGN §6)
     */
    public record ZoneSpec(SpawnZone zone, String name, String world, OptionalDouble areaRadius, int minWave, OptionalInt maxWave,
                           boolean forNormalWaves, boolean forSpecialWaves, boolean laneAutoReroute,
                           List<WorldPoint> spawnPoints, List<WorldPoint> lanePoints) {
        public ZoneSpec {
            Objects.requireNonNull(zone, "zone");
            name = name == null ? zone.id() : name;
            world = world == null ? "" : world;
            areaRadius = areaRadius == null ? OptionalDouble.empty() : areaRadius;
            maxWave = maxWave == null ? OptionalInt.empty() : maxWave;
            spawnPoints = spawnPoints == null ? List.of() : List.copyOf(spawnPoints);
            lanePoints = lanePoints == null ? List.of() : List.copyOf(lanePoints);
            if (minWave < 1) minWave = 1;
        }

        /** Whether the zone takes spawns of this round (wave range, S4-14). */
        public boolean acceptsWave(int round, boolean special) {
            if (round < minWave) return false;
            if (maxWave.isPresent() && round > maxWave.getAsInt()) return false;
            return special ? forSpecialWaves : forNormalWaves;
        }

        /** Usable = at least one spawn point or an area radius (S4-14). */
        public boolean usable() {
            return !spawnPoints.isEmpty() || (areaRadius.isPresent() && areaRadius.getAsDouble() > 0);
        }
    }

    /** The API's readiness verdict for this configuration. */
    public record Readiness(boolean ready, List<String> errors, List<String> warnings) {
        public Readiness {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public Optional<ObjectiveSpec> objective(int id) {
        return objectives.stream().filter(o -> o.id() == id).findFirst();
    }

    public Optional<GateSpec> gate(int gateStructureId) {
        return gates.stream().filter(g -> g.gateStructureId() == gateStructureId).findFirst();
    }

    /** The objective an objective gate belongs to (empty for an area gate). */
    public Optional<ObjectiveSpec> objectiveOfGate(int gateStructureId) {
        return objectives.stream().filter(o -> o.gateStructureId().isPresent() && o.gateStructureId().getAsInt() == gateStructureId).findFirst();
    }

    /** The core spawn zones (for the frontier). */
    public List<SpawnZone> spawnZones() {
        return zones.stream().map(ZoneSpec::zone).toList();
    }

    public Optional<ZoneSpec> zone(String zoneId) {
        return zones.stream().filter(z -> z.zone().id().equals(zoneId)).findFirst();
    }
}
