package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.SiegePhase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Siege Phase 8b: one lobby as the menus see it, built by knk-paper from the runtime on the main
 * thread (names and titles resolved there). Immutable; the views only format it.
 *
 * @param secondsRemaining  until the phase's next boundary (match start, match end, end of cooldown)
 * @param capacity          join capacity (the drawn scenario's PlayersMax, before the draw the largest)
 * @param scenario          the drawn scenario (from the draw until cooldown), or null
 * @param candidates        the vote candidates while matchmaking (else empty)
 * @param votesByScenario   votes per candidate scenario id
 * @param entryRequirement  the title needed to join (e.g. "Squire"), or null when there is none
 * @param members           the lobby's members (names/titles resolved), in join order
 * @param objectives        objective states while a match runs (else empty)
 * @param teams             team summaries once teams exist (split or match), else empty
 */
public record SiegeMenuSnapshot(
        int lobbyId,
        String name,
        SiegePhase phase,
        int secondsRemaining,
        boolean joinable,
        boolean votingOpen,
        int capacity,
        KnkSiegeScenario scenario,
        List<KnkSiegeScenario> candidates,
        boolean allowRandomVote,
        Map<Integer, Integer> votesByScenario,
        int randomVotes,
        String entryRequirement,
        List<Member> members,
        List<Objective> objectives,
        List<Team> teams
) {
    public SiegeMenuSnapshot {
        if (phase == null) phase = SiegePhase.DISABLED;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        votesByScenario = votesByScenario == null ? Map.of() : Map.copyOf(votesByScenario);
        members = members == null ? List.of() : List.copyOf(members);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        teams = teams == null ? List.of() : List.copyOf(teams);
    }

    /** @param teamId the member's team once teams are split, else null */
    public record Member(UUID id, String name, String titleName, String rankName, Integer teamId) {}

    /**
     * @param holderTeamId      current holder
     * @param capturePercent    0-100
     * @param lastCapturerName  the player who captured it last, or null
     * @param gateName          its gate's name, or null
     * @param gateStatus        "Open" / "Closed" / "Destroyed", or null
     * @param bannerPatterns    the live capture-gradient banner (E6 BannerPatterns string), or null
     */
    public record Objective(int objectiveId, String name, boolean instantVictory, int holderTeamId, int capturePercent,
                            boolean contested, int captureCount, String lastCapturerName, String gateName,
                            String gateStatus, String bannerPatterns) {}

    /** @param chatColor Bukkit ChatColor name; @param bannerPatterns E6 string or null */
    public record Team(int teamId, String name, String chatColor, int memberCount, int objectivesHeld,
                       String bannerPatterns) {}

    public int memberCount() {
        return members.size();
    }

    public Team team(Integer teamId) {
        if (teamId == null) return null;
        return teams.stream().filter(t -> t.teamId() == teamId).findFirst().orElse(null);
    }
}
