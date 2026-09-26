package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who is allied with whom (DESIGN §3.4): teams sharing an {@code allianceGroup} are allies, all
 * others enemies. Symmetric by construction, unlike v2's two independent ally/enemy lists. Never
 * looks at team names, which is what keeps v2's hardcoded {@code "cinixians"} bug from coming back.
 */
public final class AllianceResolver {

    private final Map<Integer, KnkSiegeTeam> teamsById = new LinkedHashMap<>();

    public AllianceResolver(List<KnkSiegeTeam> teams) {
        for (KnkSiegeTeam team : teams) {
            teamsById.put(team.id(), team);
        }
    }

    public static AllianceResolver of(KnkSiegeScenario scenario) {
        return new AllianceResolver(scenario.teams());
    }

    public boolean knows(int teamId) {
        return teamsById.containsKey(teamId);
    }

    /** @throws IllegalArgumentException if the team isn't part of this scenario */
    public int allianceOf(int teamId) {
        KnkSiegeTeam team = teamsById.get(teamId);
        if (team == null) throw new IllegalArgumentException("Unknown siege team " + teamId);
        return team.allianceGroup();
    }

    /** Same alliance group (a team is its own ally). Unknown teams are nobody's ally. */
    public boolean areAllies(int teamA, int teamB) {
        return knows(teamA) && knows(teamB) && allianceOf(teamA) == allianceOf(teamB);
    }

    /** Both teams known and in different alliance groups. */
    public boolean areEnemies(int teamA, int teamB) {
        return knows(teamA) && knows(teamB) && allianceOf(teamA) != allianceOf(teamB);
    }

    /** Every alliance group, ascending. */
    public Set<Integer> alliances() {
        Set<Integer> groups = new TreeSet<>();
        teamsById.values().forEach(t -> groups.add(t.allianceGroup()));
        return Collections.unmodifiableSet(groups);
    }

    /** Team ids in an alliance, in scenario order. */
    public List<Integer> teamsOf(int allianceGroup) {
        return teamsById.values().stream().filter(t -> t.allianceGroup() == allianceGroup).map(KnkSiegeTeam::id).toList();
    }

    /** True when the alliance contains a team with role Defender (timeout tie-break, DESIGN §7.5). */
    public boolean hasDefender(int allianceGroup) {
        return teamsById.values().stream().anyMatch(t -> t.allianceGroup() == allianceGroup && t.isDefender());
    }

    /** Alliance groups with at least one member, from per-team member counts (elimination, §6.8). */
    public Set<Integer> alliancesWithMembers(Map<Integer, Integer> membersPerTeam) {
        Set<Integer> groups = new TreeSet<>();
        membersPerTeam.forEach((teamId, count) -> {
            if (count != null && count > 0 && knows(teamId)) groups.add(allianceOf(teamId));
        });
        return Collections.unmodifiableSet(groups);
    }
}
