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
 * Inside one group the scenario's objective order holds. Objectives captured for good (nothing left to
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

        List<Target> objectives = new ArrayList<>();
        for (ObjectiveState state : board.objectives()) {
            KnkSiegeObjective objective = state.objective();
            if (state.isCapturedFinal() || !hasPosition(objective.captureLocation())) {
                continue;
            }
            Kind kind = alliances.allianceOf(state.holderTeamId()) == alliance ? Kind.DEFEND : Kind.CAPTURE;
            int percent = objective.capturePoints() <= 0 ? 0 : Math.round(100f * state.points() / objective.capturePoints());
            objectives.add(new Target("objective:" + objective.id(), objective.name(), kind, state.isContested(),
                    objective.captureLocation(), percent));
        }
        // List.sort is stable: the scenario's order survives inside each group.
        objectives.sort(Comparator.comparingInt((Target t) -> t.underAttack() ? 0 : 1)
                .thenComparingInt(t -> t.kind() == primary ? 0 : 1));

        List<Target> out = new ArrayList<>(objectives);
        team.get().defaultSpawnpoint()
                .filter(spawn -> hasPosition(spawn.location()))
                .ifPresent(spawn -> out.add(spawnTarget(spawn)));
        return List.copyOf(out);
    }

    private static Target spawnTarget(KnkSiegeSpawnpoint spawn) {
        String name = spawn.name() == null || spawn.name().isBlank() ? "Team spawn" : spawn.name();
        return new Target("spawn:" + spawn.id(), name, Kind.SPAWN, false, spawn.location(), -1);
    }

    private static boolean hasPosition(KnkLocation location) {
        return location != null && location.x() != null && location.y() != null && location.z() != null && location.world() != null;
    }
}
