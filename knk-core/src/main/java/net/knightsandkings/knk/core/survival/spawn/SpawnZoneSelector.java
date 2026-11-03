package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.SurvivalPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Picks the spawnzone for the next spawn among the active zones (DESIGN §6, S50). The "favor spawning close to
 * players" slider (0–100 %) is the share of spawns that go to the {@code nearZoneCount} active zones nearest to the
 * living players (ghosts ignored); the rest goes to the other active zones, each share drawn by zone weight. 0 %
 * = zone weight only. Earlier zones stay active and keep spawning; the slider is a preference: the far zones are
 * never deactivated, and when there are no far zones (or no living player position) everything is drawn by weight.
 */
public final class SpawnZoneSelector {
    public static final int DEFAULT_NEAR_ZONE_COUNT = 1;

    private final int nearPlayersPercent;
    private final int nearZoneCount;

    /**
     * @param nearPlayersPercent the slider, 0–100
     * @param nearZoneCount      how many of the nearest active zones form the "near" set (at least 1)
     */
    public SpawnZoneSelector(int nearPlayersPercent, int nearZoneCount) {
        if (nearPlayersPercent < 0 || nearPlayersPercent > 100) throw new IllegalArgumentException("slider must be 0..100");
        if (nearZoneCount < 1) throw new IllegalArgumentException("nearZoneCount must be >= 1");
        this.nearPlayersPercent = nearPlayersPercent;
        this.nearZoneCount = nearZoneCount;
    }

    public SpawnZoneSelector(int nearPlayersPercent) {
        this(nearPlayersPercent, DEFAULT_NEAR_ZONE_COUNT);
    }

    public int nearPlayersPercent() {
        return nearPlayersPercent;
    }

    /** The active zones sorted by distance to the nearest living player (nearest first); empty input stays empty. */
    public static List<SpawnZone> byDistanceToPlayers(List<SpawnZone> activeZones, List<SurvivalPoint> livingPlayers) {
        List<SpawnZone> sorted = new ArrayList<>(activeZones);
        sorted.sort(Comparator.comparingDouble(z -> distanceToNearestPlayer(z, livingPlayers)));
        return List.copyOf(sorted);
    }

    private static double distanceToNearestPlayer(SpawnZone zone, List<SurvivalPoint> livingPlayers) {
        double best = Double.POSITIVE_INFINITY;
        for (SurvivalPoint p : livingPlayers) best = Math.min(best, zone.anchor().distanceSquaredTo(p));
        return best;
    }

    /**
     * The zone for the next planned spawn.
     *
     * @param activeZones   the frontier's active zones
     * @param livingPlayers positions of the living players (ghosts excluded); may be empty
     */
    public Optional<SpawnZone> select(List<SpawnZone> activeZones, List<SurvivalPoint> livingPlayers, RandomGenerator random) {
        if (activeZones.isEmpty()) return Optional.empty();
        if (nearPlayersPercent == 0 || livingPlayers.isEmpty() || activeZones.size() <= nearZoneCount) {
            return WeightedPick.pick(activeZones, SpawnZone::weight, random);
        }
        List<SpawnZone> sorted = byDistanceToPlayers(activeZones, livingPlayers);
        List<SpawnZone> near = sorted.subList(0, nearZoneCount);
        List<SpawnZone> far = sorted.subList(nearZoneCount, sorted.size());
        boolean chooseNear = random.nextInt(100) < nearPlayersPercent;
        return WeightedPick.pick(chooseNear ? near : far, SpawnZone::weight, random);
    }

    /**
     * The zone for a stuck respawn (§3c rule 1: "preferring zones nearest the players"): the nearest active zone,
     * or a weighted pick when no living player position is known.
     */
    public Optional<SpawnZone> selectNearestPlayers(List<SpawnZone> activeZones, List<SurvivalPoint> livingPlayers, RandomGenerator random) {
        if (activeZones.isEmpty()) return Optional.empty();
        if (livingPlayers.isEmpty()) return WeightedPick.pick(activeZones, SpawnZone::weight, random);
        return Optional.of(byDistanceToPlayers(activeZones, livingPlayers).get(0));
    }

    /**
     * Readiness (§6): at least one initial usable zone, prerequisite references resolve, no duplicate ids.
     *
     * @param knownObjectiveIds the scenario's objective ids
     */
    public static List<ReadinessIssue> readiness(List<SpawnZone> zones, Set<String> knownObjectiveIds) {
        List<ReadinessIssue> issues = new ArrayList<>();
        if (zones.stream().noneMatch(SpawnZone::isInitial)) {
            issues.add(ReadinessIssue.error("spawn.no-initial-zone", "At least one spawnzone must be active from the start"));
        }
        Set<String> seen = new HashSet<>();
        for (SpawnZone zone : zones) {
            if (!seen.add(zone.id())) {
                issues.add(ReadinessIssue.error("spawn.duplicate-zone", "Spawnzone id used twice: " + zone.id()));
            }
            for (String objective : zone.prerequisites()) {
                if (!knownObjectiveIds.contains(objective)) {
                    issues.add(ReadinessIssue.error("spawn.unknown-objective",
                        "Spawnzone " + zone.id() + " references unknown objective " + objective));
                }
            }
        }
        return List.copyOf(issues);
    }
}
