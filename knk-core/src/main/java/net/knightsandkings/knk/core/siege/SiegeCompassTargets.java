package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What the navigation compass cycles through in a running siege match (KNG-234), ordered for one player's team.
 *
 * <p>Relevance is <b>holder-relative</b>, like the capture rules ({@link ObjectiveState}): an objective held by
 * the team's alliance is one to {@link Kind#DEFEND}, any other is one to {@link Kind#CAPTURE}, whatever the
 * teams are called and however often the sides swapped through recapture. Order:
 * <ol>
 *   <li>objectives under attack (an enemy of the holder stands in the radius) - first, so the compass sends
 *       defenders to the fight and attackers to the capture in progress;</li>
 *   <li>the team's own job: defenders their objectives, attackers the ones to capture;</li>
 *   <li>the other kind;</li>
 *   <li>the team's spawnpoint, last (new players lose their way back too).</li>
 * </ol>
 * Inside one group the <b>front line</b> comes first (developer request 2026-10-23: the objectives that are attacked
 * first are the ones to head for, for both teams): regular objectives before an instant-victory ("main") one, nearest
 * to where the attackers spawn first, then the scenario's own order. Objectives captured for good (nothing left to
 * defend or take) and objectives without a location are left out.
 */
public final class SiegeCompassTargets {
    private SiegeCompassTargets() {
    }

    public enum Kind {
        DEFEND,
        CAPTURE,
        SPAWN
    }

    /**
     * One entry of the compass cycle.
     *
     * @param key             stable id across re-orderings: {@code objective:<id>} or {@code spawn:<id>}
     * @param label           display name
     * @param kind            defend, capture or the team's spawn
     * @param underAttack     enemies of its holder stand in the radius now
     * @param location        where to navigate to (never null)
     * @param progressPercent capture points left as a percentage of the objective's total; -1 for a spawn
     */
    public record Target(String key, String label, Kind kind, boolean underAttack, KnkLocation location, int progressPercent) {
    }

    public static List<Target> forTeam(KnkSiegeScenario scenario, SiegeObjectiveBoard board, AllianceResolver alliances, int teamId) {
        Optional<KnkSiegeTeam> team = scenario.team(teamId);
        if (team.isEmpty() || !alliances.knows(teamId)) {
            return List.of();
        }
        int alliance = alliances.allianceOf(teamId);
        Kind primary = team.get().isDefender() ? Kind.DEFEND : Kind.CAPTURE;

        List<double[]> attackerSpawns = attackerSpawnPoints(scenario);
        // an objective with how far the attackers have to come to reach it, and whether taking it ends the match
        record Ranked(Target target, boolean instantVictory, double frontLine) { }
        List<Ranked> ranked = new ArrayList<>();
        for (ObjectiveState state : board.objectives()) {
            KnkSiegeObjective objective = state.objective();
            if (state.isCapturedFinal() || !hasPosition(objective.captureLocation())) {
                continue;
            }
            Kind kind = alliances.allianceOf(state.holderTeamId()) == alliance ? Kind.DEFEND : Kind.CAPTURE;
            int percent = objective.capturePoints() <= 0 ? 0 : Math.round(100f * state.points() / objective.capturePoints());
            Target target = new Target("objective:" + objective.id(), objective.name(), kind, state.isContested(),
                    objective.captureLocation(), percent);
            ranked.add(new Ranked(target, objective.instantVictory(), distanceToNearest(attackerSpawns, objective.captureLocation())));
        }
        // List.sort is stable: the scenario's own order is the last tie-break.
        ranked.sort(Comparator.comparingInt((Ranked r) -> r.target().underAttack() ? 0 : 1)
                .thenComparingInt(r -> r.target().kind() == primary ? 0 : 1)
                .thenComparing(Ranked::instantVictory)
                .thenComparingDouble(Ranked::frontLine));
        List<Target> objectives = ranked.stream().map(Ranked::target).toList();

        List<Target> out = new ArrayList<>(objectives);
        team.get().defaultSpawnpoint()
                .filter(spawn -> hasPosition(spawn.location()))
                .ifPresent(spawn -> out.add(spawnTarget(spawn)));
        return List.copyOf(out);
    }

    /** Where the attacking side starts: the spawnpoints of every attacker-role team that have a position. */
    private static List<double[]> attackerSpawnPoints(KnkSiegeScenario scenario) {
        List<double[]> out = new ArrayList<>();
        for (KnkSiegeTeam team : scenario.teams()) {
            if (team.isDefender()) {
                continue;
            }
            for (KnkSiegeSpawnpoint spawn : team.spawnpoints()) {
                if (hasPosition(spawn.location())) {
                    out.add(new double[] {spawn.location().x(), spawn.location().z()});
                }
            }
        }
        return out;
    }

    /** Horizontal distance to the nearest of the points; 0 without any, which leaves the scenario's order alone. */
    private static double distanceToNearest(List<double[]> points, KnkLocation to) {
        double best = Double.MAX_VALUE;
        for (double[] p : points) {
            best = Math.min(best, Math.hypot(p[0] - to.x(), p[1] - to.z()));
        }
        return points.isEmpty() ? 0 : best;
    }

    private static Target spawnTarget(KnkSiegeSpawnpoint spawn) {
        String name = spawn.name() == null || spawn.name().isBlank() ? "Team spawn" : spawn.name();
        return new Target("spawn:" + spawn.id(), name, Kind.SPAWN, false, spawn.location(), -1);
    }

    private static boolean hasPosition(KnkLocation location) {
        return location != null && location.x() != null && location.y() != null && location.z() != null && location.world() != null;
    }
}
