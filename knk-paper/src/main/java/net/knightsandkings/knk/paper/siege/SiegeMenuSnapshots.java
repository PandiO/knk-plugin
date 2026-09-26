package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions;
import net.knightsandkings.knk.core.siege.SiegeSpawnOptions.SpawnOption;
import net.knightsandkings.knk.core.siege.VoteTally;
import net.knightsandkings.knk.core.siege.menu.SiegeBodyRowView;
import net.knightsandkings.knk.core.siege.menu.SiegeLobbyMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeMenuFormat;
import net.knightsandkings.knk.core.siege.menu.SiegeMenuSnapshot;
import net.knightsandkings.knk.core.siege.menu.SiegeServerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeViewerMenuView;
import net.knightsandkings.knk.core.siege.menu.SiegeVoteOptionView;
import net.knightsandkings.knk.core.siege.menu.SpawnOptionView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Siege Phase 8b: turns the runtime ({@link SiegeLobbyRuntime}, {@link SiegeMatch}) into knk-core's
 * menu snapshot and views. Main thread only (menu providers and sources run there). Reads only.
 */
final class SiegeMenuSnapshots {
    private SiegeMenuSnapshots() {}

    /** Matchmaking first, then running, then the rest (fixes N5). */
    private static final Comparator<SiegeLobbyRuntime> OVERVIEW_ORDER = Comparator
            .comparingInt((SiegeLobbyRuntime rt) -> switch (rt.phase()) {
                case MATCHMAKING -> 0;
                case HUB -> 1;
                case IN_PROGRESS -> 2;
                case ENDING -> 3;
                case COOLDOWN -> 4;
                case DISABLED -> 5;
            })
            .thenComparing(SiegeLobbyRuntime::displayName, String.CASE_INSENSITIVE_ORDER);

    static List<SiegeLobbyRuntime> ordered(SiegeService service) {
        List<SiegeLobbyRuntime> lobbies = new ArrayList<>(service.lobbies());
        lobbies.sort(OVERVIEW_ORDER);
        return lobbies;
    }

    static Optional<SiegeLobbyRuntime> lobby(SiegeService service, String lobbyId) {
        if (lobbyId == null || lobbyId.isBlank()) return Optional.empty();
        try {
            int id = Integer.parseInt(lobbyId.trim());
            return service.lobbies().stream().filter(rt -> rt.id() == id).findFirst();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static SiegeMenuSnapshot snapshot(SiegeService service, SiegeLobbyRuntime rt) {
        SiegeLobbyStateMachine machine = rt.machine();
        SiegeMatch match = rt.match().orElse(null);
        KnkSiegeScenario scenario = match != null ? match.scenario() : rt.drawnScenario().orElse(null);
        List<KnkSiegeScenario> candidates = rt.phase() == SiegePhase.MATCHMAKING ? machine.candidates() : List.of();
        VoteTally tally = machine.voteTally().orElse(null);
        Map<Integer, Integer> votes = new HashMap<>();
        if (tally != null) candidates.forEach(c -> votes.put(c.id(), tally.votesFor(c.id())));
        int minXp = machine.joinMinTitleExperience();
        String entry = minXp > 0 ? service.titleRanks().requirementLabel(minXp) : null;

        Map<UUID, Integer> teamOf = new HashMap<>();
        if (match != null) {
            match.roster().members().forEach(m -> teamOf.put(m.playerId(), m.teamId()));
        } else {
            rt.split().ifPresent(split -> split.forEach((teamId, players) -> players.forEach(p -> teamOf.put(p, teamId))));
        }

        List<SiegeMenuSnapshot.Member> members = new ArrayList<>();
        for (UUID id : rt.members()) {
            UserSummary user = service.cachedUser(id).orElse(null);
            members.add(new SiegeMenuSnapshot.Member(id, nameOf(id, user), user == null ? null : user.titleName(),
                    user == null ? null : user.premiumTierName(), teamOf.get(id)));
        }

        List<SiegeMenuSnapshot.Team> teams = new ArrayList<>();
        if (scenario != null && !teamOf.isEmpty()) {
            for (KnkSiegeTeam team : scenario.teams()) {
                int count = (int) teamOf.values().stream().filter(t -> t == team.id()).count();
                int held = match != null
                        ? (int) match.board().objectives().stream().filter(o -> o.holderTeamId() == team.id()).count()
                        : (int) scenario.objectives().stream().filter(o -> o.initialHolderTeamId() == team.id()).count();
                teams.add(new SiegeMenuSnapshot.Team(team.id(), teamName(team), team.chatColor(), count, held,
                        SiegeMenuFormat.bannerPatterns(team.bannerDesign())));
            }
        }

        List<SiegeMenuSnapshot.Objective> objectives = new ArrayList<>();
        if (match != null) {
            for (ObjectiveState state : match.board().objectives()) {
                List<CaptureEvent> captures = state.captures();
                String lastCapturer = captures.isEmpty() ? null : nameOf(captures.get(captures.size() - 1).capturerId(), null);
                Integer gateId = state.objective().gateStructureId();
                String gateName = gateId == null ? null : scenario.gates().stream()
                        .filter(g -> g.gateStructureId() == gateId).map(KnkSiegeGate::name).findFirst().orElse("#" + gateId);
                KnkSiegeTeam holder = scenario.team(state.holderTeamId()).orElse(null);
                objectives.add(new SiegeMenuSnapshot.Objective(state.objectiveId(), state.objective().name(),
                        state.objective().instantVictory(), state.holderTeamId(), state.capturePercent(), state.isContested(),
                        captures.size(), lastCapturer, gateName, null,
                        holder == null ? null : SiegeMenuFormat.bannerPatterns(holder.bannerDesign())));
            }
        }

        return new SiegeMenuSnapshot(rt.id(), rt.displayName(), rt.phase(), machine.secondsRemaining(), machine.isJoinable(),
                machine.isVotingOpen(), machine.joinCapacity(), scenario, candidates, tally != null && tally.allowRandomVote(),
                votes, tally == null ? 0 : tally.randomVotes(), entry, members, objectives, teams);
    }

    static SiegeLobbyMenuView lobbyView(SiegeService service, SiegeLobbyRuntime rt, Player viewer) {
        boolean member = viewer != null && rt.isMember(viewer.getUniqueId());
        String denial = viewer == null || member || !rt.machine().isJoinable() ? null
                : service.joinDenial(viewer, rt).orElse(null);
        return new SiegeLobbyMenuView(snapshot(service, rt), member, denial);
    }

    /** @param rt the lobby the menu is about, or null (then the viewer's own lobby, if any) */
    static SiegeViewerMenuView viewerView(SiegeService service, SiegeLobbyRuntime rt, Player viewer) {
        if (viewer == null) return SiegeViewerMenuView.outsider(null);
        UUID id = viewer.getUniqueId();
        SiegeLobbyRuntime lobby = rt != null ? rt : service.lobbyOf(id).orElse(null);
        if (lobby == null) return SiegeViewerMenuView.outsider(null);
        boolean member = lobby.isMember(id);
        if (!member) {
            String denial = lobby.machine().isJoinable() ? service.joinDenial(viewer, lobby).orElse(null) : null;
            return SiegeViewerMenuView.outsider(denial);
        }
        SiegeMenuSnapshot.Team team = null;
        String spawnName = null;
        SiegeMatch match = lobby.match().orElse(null);
        if (match != null) {
            KnkSiegeTeam own = match.teamOf(id).orElse(null);
            if (own != null) {
                team = new SiegeMenuSnapshot.Team(own.id(), teamName(own), own.chatColor(),
                        match.roster().membersOf(own.id()).size(),
                        (int) match.board().objectives().stream().filter(o -> o.holderTeamId() == own.id()).count(),
                        SiegeMenuFormat.bannerPatterns(own.bannerDesign()));
                SpawnChoice choice = match.roster().spawnChoice(id).orElse(null);
                spawnName = SiegeSpawnOptions.forTeam(own, match.board(), choice).stream()
                        .filter(SpawnOption::current).map(SpawnOption::name).findFirst().orElse(null);
            }
        }
        return new SiegeViewerMenuView(true, null, team, spawnName);
    }

    static SiegeServerMenuView serverView(SiegeService service, Player viewer) {
        List<SiegeLobbyRuntime> lobbies = service.lobbies();
        int playing = lobbies.stream().mapToInt(SiegeLobbyRuntime::memberCount).sum();
        String own = viewer == null ? null : service.lobbyOf(viewer.getUniqueId()).map(SiegeLobbyRuntime::displayName).orElse(null);
        return new SiegeServerMenuView(lobbies.size(), playing, own);
    }

    static List<SiegeVoteOptionView> voteOptions(SiegeService service, SiegeLobbyRuntime rt, Player viewer) {
        if (!rt.machine().isVotingOpen()) return List.of();
        VoteTally tally = rt.machine().voteTally().orElse(null);
        if (tally == null) return List.of();
        boolean member = viewer != null && rt.isMember(viewer.getUniqueId());
        VoteTally.VoteChoice mine = viewer == null ? null : tally.choiceOf(viewer.getUniqueId()).orElse(null);
        List<SiegeVoteOptionView> rows = new ArrayList<>();
        for (KnkSiegeScenario candidate : rt.machine().candidates()) {
            Integer minXp = candidate.minTitleExperience();
            String entry = minXp != null && minXp > 0 ? service.titleRanks().requirementLabel(minXp) : null;
            boolean voted = mine != null && !mine.isRandom() && mine.scenarioId() == candidate.id();
            rows.add(new SiegeVoteOptionView(candidate, tally.votesFor(candidate.id()), voted, member, entry));
        }
        if (tally.allowRandomVote()) {
            rows.add(new SiegeVoteOptionView(null, tally.randomVotes(), mine != null && mine.isRandom(), member, null));
        }
        return rows;
    }

    /** Members before the match (matchmaking/hub), objectives during it, nothing in cooldown. */
    static List<SiegeBodyRowView> body(SiegeService service, SiegeLobbyRuntime rt, Player viewer) {
        SiegeMenuSnapshot snapshot = snapshot(service, rt);
        List<SiegeBodyRowView> rows = new ArrayList<>();
        switch (rt.phase()) {
            case MATCHMAKING, HUB -> snapshot.members().forEach(m -> rows.add(SiegeBodyRowView.member(m,
                    viewer != null && viewer.getUniqueId().equals(m.id()), snapshot.team(m.teamId()))));
            case IN_PROGRESS, ENDING -> snapshot.objectives().forEach(o -> rows.add(SiegeBodyRowView.objective(o,
                    snapshot.team(o.holderTeamId()), snapshot.scenario() != null && snapshot.scenario().allowRecapture())));
            case COOLDOWN, DISABLED -> { }
        }
        return rows;
    }

    static List<SpawnOptionView> spawnOptions(SiegeService service, Player viewer) {
        if (viewer == null) return List.of();
        SiegeMatch match = service.runningMatchOf(viewer.getUniqueId()).orElse(null);
        if (match == null) return List.of();
        KnkSiegeTeam team = match.teamOf(viewer.getUniqueId()).orElse(null);
        if (team == null) return List.of();
        String banner = SiegeMenuFormat.bannerPatterns(team.bannerDesign());
        SpawnChoice choice = match.roster().spawnChoice(viewer.getUniqueId()).orElse(null);
        return SiegeSpawnOptions.forTeam(team, match.board(), choice).stream()
                .map(option -> new SpawnOptionView(option, banner))
                .toList();
    }

    private static String nameOf(UUID id, UserSummary user) {
        Player online = id == null ? null : Bukkit.getPlayer(id);
        if (online != null) return online.getName();
        if (user != null && user.username() != null) return user.username();
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private static String teamName(KnkSiegeTeam team) {
        return team.name() == null || team.name().isBlank() ? "Team " + team.id() : team.name().trim();
    }
}
