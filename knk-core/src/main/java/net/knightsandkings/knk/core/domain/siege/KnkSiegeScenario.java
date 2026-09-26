package net.knightsandkings.knk.core.domain.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A fully resolved, ready scenario as runtime-config sends it (DESIGN §3.3–3.7). Every default is
 * already applied server-side: team identity (team value, else the clan's), the "first Defender"
 * objective holder and gate owner, the objective capture point from its gate, and
 * {@code isObjectiveGate}. Nothing here re-derives them.
 * <p>
 * Teams, spawnpoints and objectives are kept in (sortOrder, id) order whatever order they arrive in.
 *
 * @param townWgRegionId     the town's WorldGuard region id, or null
 * @param minTitleBracketId  minimum title bracket to join, or null for none
 * @param minTitleExperience that bracket's MinExperience, so entry can be checked without a lookup
 * @param areaGateStructureIds the other gate structures in the scenario area (its districts, or the
 *                           town's), which a match forces open and invincible (Phase 7, DESIGN §8.1)
 */
public record KnkSiegeScenario(
        int id,
        String name,
        String description,
        int townId,
        String townName,
        String townWgRegionId,
        List<KnkSiegeDistrict> districts,
        KnkLocation hubLocation,
        int playersMin,
        int playersMax,
        Integer minTitleBracketId,
        Integer minTitleExperience,
        KnkSiegeMatchLength matchLength,
        KnkSiegeRewards rewards,
        boolean lockdownScenarioArea,
        boolean allowRecapture,
        boolean enchantDropsEnabled,
        List<KnkSiegeTeam> teams,
        List<KnkSiegeObjective> objectives,
        List<KnkSiegeGate> gates,
        List<Integer> areaGateStructureIds
) {
    private static final Comparator<KnkSiegeTeam> TEAM_ORDER =
            Comparator.comparingInt(KnkSiegeTeam::sortOrder).thenComparingInt(KnkSiegeTeam::id);
    private static final Comparator<KnkSiegeObjective> OBJECTIVE_ORDER =
            Comparator.comparingInt(KnkSiegeObjective::sortOrder).thenComparingInt(KnkSiegeObjective::id);

    public KnkSiegeScenario {
        districts = districts == null ? List.of() : List.copyOf(districts);
        teams = teams == null ? List.of() : teams.stream().sorted(TEAM_ORDER).toList();
        objectives = objectives == null ? List.of() : objectives.stream().sorted(OBJECTIVE_ORDER).toList();
        gates = gates == null ? List.of() : List.copyOf(gates);
        areaGateStructureIds = areaGateStructureIds == null ? List.of()
                : areaGateStructureIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (matchLength == null) matchLength = KnkSiegeMatchLength.DEFAULT;
        if (rewards == null) rewards = KnkSiegeRewards.NONE;
    }

    public Optional<KnkSiegeTeam> team(int teamId) {
        return teams.stream().filter(t -> t.id() == teamId).findFirst();
    }

    /** The first team (sortOrder, id) with role Defender; ready scenarios always have one. */
    public Optional<KnkSiegeTeam> firstDefender() {
        return teams.stream().filter(t -> t.role() == SiegeTeamRole.DEFENDER).findFirst();
    }

    public Optional<KnkSiegeObjective> objective(int objectiveId) {
        return objectives.stream().filter(o -> o.id() == objectiveId).findFirst();
    }

    public Optional<KnkSiegeGate> gate(int gateStructureId) {
        return gates.stream().filter(g -> g.gateStructureId() == gateStructureId).findFirst();
    }

    public List<KnkSiegeObjective> instantVictoryObjectives() {
        return objectives.stream().filter(KnkSiegeObjective::instantVictory).toList();
    }

    public int nonInstantVictoryObjectiveCount() {
        return (int) objectives.stream().filter(o -> !o.instantVictory()).count();
    }

    /** Gate structure ids of the admin-selected gates (DESIGN §3.7). */
    public List<Integer> gateStructureIds() {
        return gates.stream().map(KnkSiegeGate::gateStructureId).toList();
    }
}
