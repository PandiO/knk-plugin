package net.knightsandkings.knk.paper.siege;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.paper.utils.ViewerScoreboards;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.knightsandkings.knk.paper.utils.ScoreboardUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-team scoreboards during a match (DESIGN §6.5, §6.6): one {@link Scoreboard} per siege team so the
 * sidebar can be viewer-relative - the team, the time left, and the objectives (instant-victory first)
 * coloured green when the viewer's alliance holds them and red otherwise, with a marker while
 * contested. Every siege team is a Bukkit team on each board, so name tags carry team colours.
 * <p>
 * Alpha run 1 (PandiO/knk-workspace#163): only the match's <b>members</b> see these boards. The tab list
 * shows {@code [Team] name} through the board's team prefix and {@code [K/D]} through a tab-list objective on the
 * siege boards, so nothing global changes a player's name: everyone else keeps the shared {@code ScoreboardUtil}
 * board, where a member carries a {@code " [Siege]"} suffix (tab list and name tag, {@link ScoreboardUtil#setSiegeTag})
 * from the start (or their late join) until they are restored. A member keeps their team's board through the
 * post-match wait at the hub (#198), which shows the result and the time until the return.
 * <p>
 * The player's previous scoreboard (normally {@code ScoreboardUtil}'s shared one) is restored when they
 * leave the match or are released after it. Every board assignment goes through {@link #setBoard}/{@link #boardOf}
 * (the one place package G's {@code ViewerScoreboards} wraps for the Flash Chaos name-tag blackout).
 * <p>
 * Siege shop P18 (PandiO/knk-workspace#866): with a {@link PersonalLine} (siege points on) every member gets a board
 * of their own instead, with the same team lines plus their personal line (their siege points) after the clock.
 * Without one (the default) the boards stay one per team, exactly as before.
 */
public final class SiegeScoreboardPresenter implements SiegeMatchObserver {

    private static final int MAX_LINES = 15;

    /** The tab-list objective on the siege boards: each member's {@code [kills/deaths]} (#163). */
    static final String TAB_OBJECTIVE = "siege_kd";

    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Map<String, Map<Integer, Scoreboard>> boardsByMatch = new HashMap<>();
    /** Matches that ended and wait at the hub (#198): their boards show the result. */
    private final Map<String, Boolean> endedMatches = new HashMap<>();

    /** Siege shop P18 (#866): one member's own sidebar line; null when they have none. */
    @FunctionalInterface
    public interface PersonalLine {
        Component line(SiegeMatch match, UUID playerId);
    }

    /** #866: null keeps one board per team (siege points off). */
    private final PersonalLine personalLine;
    /** #866: each member's own board per match, only with a {@link #personalLine}. */
    private final Map<String, Map<UUID, Scoreboard>> playerBoardsByMatch = new HashMap<>();

    public SiegeScoreboardPresenter() {
        this(null);
    }

    public SiegeScoreboardPresenter(PersonalLine personalLine) {
        this.personalLine = personalLine;
    }

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, Scoreboard> boards = new HashMap<>();
        for (KnkSiegeTeam viewerTeam : match.scenario().teams()) {
            boards.put(viewerTeam.id(), newBoard(lobby, match, viewerTeam));
        }
        boardsByMatch.put(match.matchToken(), boards);
        if (personalLine != null) playerBoardsByMatch.put(match.matchToken(), new HashMap<>());

        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            show(lobby, match, boards, p, member.teamId());
        }
        refresh(lobby, match);
    }

    /** A board seen from {@code viewerTeam}: every siege team as a Bukkit team, the sidebar and the tab-list K/D. */
    private static Scoreboard newBoard(SiegeLobbyRuntime lobby, SiegeMatch match, KnkSiegeTeam viewerTeam) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        for (KnkSiegeTeam team : match.scenario().teams()) {
            Team bukkitTeam = board.registerNewTeam("siege_" + team.id());
            bukkitTeam.color(SiegeBukkit.color(team));
            bukkitTeam.prefix(Component.text("[" + SiegeBukkit.teamName(team) + "] ", SiegeBukkit.color(team)));
            bukkitTeam.setAllowFriendlyFire(true); // the combat listener decides; never Bukkit's team rule
            bukkitTeam.setCanSeeFriendlyInvisibles(team.id() == viewerTeam.id());
        }
        Objective sidebar = board.registerNewObjective("siege", Criteria.DUMMY,
                Component.text(lobby.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebar.numberFormat(NumberFormat.blank());
        // #163: kills/deaths in the tab list for the members only (was a global custom tab name).
        Objective tab = board.registerNewObjective(TAB_OBJECTIVE, Criteria.DUMMY, Component.text("K/D"));
        tab.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        return board;
    }

    /** A rejoin or admin join (PandiO/knk-workspace#150, #152): the late member gets their team's board too. */
    @Override
    public void memberJoinedMatch(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards == null) return;
        match.roster().teamOf(player.getUniqueId()).ifPresent(teamId -> show(lobby, match, boards, player, teamId));
        refresh(lobby, match);
    }

    private void show(SiegeLobbyRuntime lobby, SiegeMatch match, Map<Integer, Scoreboard> boards, Player p, int teamId) {
        Scoreboard own = boards.get(teamId);
        Map<UUID, Scoreboard> personal = playerBoardsByMatch.get(match.matchToken());
        KnkSiegeTeam viewerTeam = match.scenario().team(teamId).orElse(null);
        if (personal != null && viewerTeam != null) {
            // #866: the member's own board, with every member already in the match on their team.
            own = newBoard(lobby, match, viewerTeam);
            personal.put(p.getUniqueId(), own);
            for (MemberView member : match.roster().members()) {
                Player other = Bukkit.getPlayer(member.playerId());
                Team team = own.getTeam("siege_" + member.teamId());
                if (other != null && team != null) team.addEntry(other.getName());
            }
        }
        allBoards(match, boards).forEach(b -> {
            Team team = b.getTeam("siege_" + teamId);
            if (team != null) team.addEntry(p.getName());
        });
        previousBoards.putIfAbsent(p.getUniqueId(), boardOf(p));
        if (own != null) setBoard(p, own);
        ScoreboardUtil.setSiegeTag(p, true);
    }

    // ==================== Board assignment (one place, see the class comment) ====================

    /** Gives the player this board, through a #169 name-tag blackout so the board kept and handed back is the real one. */
    private static void setBoard(Player player, Scoreboard board) {
        ViewerScoreboards.set(player, board);
    }

    /** The player's own board (not a #169 blackout board). */
    private static Scoreboard boardOf(Player player) {
        return ViewerScoreboards.current(player);
    }

    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step, Map<Integer, List<Presence>> presence) {
        refresh(lobby, match);
    }

    @Override
    public void memberRemoved(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards != null) {
            allBoards(match, boards).forEach(b -> {
                Team t = b.getEntryTeam(player.getName());
                if (t != null) t.removeEntry(player.getName());
            });
        }
        Map<UUID, Scoreboard> personal = playerBoardsByMatch.get(match.matchToken());
        if (personal != null) personal.remove(player.getUniqueId()); // #866
        restore(player);
    }

    /**
     * The match ended (#198): its members keep their board through the post-match wait, which shows the result;
     * they are restored when they are released ({@link #memberRemoved}). Without a wait that is right away.
     */
    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        if (!boardsByMatch.containsKey(match.matchToken())) return;
        endedMatches.put(match.matchToken(), Boolean.TRUE);
        refresh(lobby, match);
    }

    @Override
    public void postMatchSecond(SiegeLobbyRuntime lobby, SiegeMatch match) {
        refresh(lobby, match);
    }

    /** Everyone was released: anyone still holding a board of the round gets their own back, and the boards go. */
    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        lobby.match().ifPresent(match -> {
            for (UUID id : match.roster().playerIds()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    restore(p);
                } else {
                    previousBoards.remove(id);
                    ScoreboardUtil.forgetSiegeTag(id);
                }
            }
            boardsByMatch.remove(match.matchToken());
            playerBoardsByMatch.remove(match.matchToken());
            endedMatches.remove(match.matchToken());
        });
    }

    @Override
    public void shutdown() {
        for (UUID id : new ArrayList<>(previousBoards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        previousBoards.clear();
        boardsByMatch.clear();
        playerBoardsByMatch.clear();
        endedMatches.clear();
    }

    private void restore(Player player) {
        Scoreboard previous = previousBoards.remove(player.getUniqueId());
        if (previous != null) setBoard(player, previous);
        ScoreboardUtil.setSiegeTag(player, false);
    }

    // ==================== Rendering ====================

    private void refresh(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards == null) return;
        int secondsLeft = lobby.machine().secondsRemaining();
        boolean ended = endedMatches.containsKey(match.matchToken());
        List<ObjectiveState> objectives = new ArrayList<>(match.board().objectives());
        objectives.sort(Comparator.comparing((ObjectiveState s) -> !s.objective().instantVictory()));

        for (KnkSiegeTeam viewerTeam : match.scenario().teams()) {
            Scoreboard board = boards.get(viewerTeam.id());
            if (board == null) continue;
            int viewerAlliance = viewerTeam.allianceGroup();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("Team: ", NamedTextColor.GRAY).append(SiegeBukkit.teamComponent(viewerTeam)));
            if (ended) {
                lines.add(Component.text("Match over", NamedTextColor.GOLD, TextDecoration.BOLD));
                lines.add(Component.text("Return in: ", NamedTextColor.GRAY)
                        .append(Component.text(SiegeMessages.clock(secondsLeft), NamedTextColor.WHITE)));
            } else {
                lines.add(Component.text("Time left: ", NamedTextColor.GRAY)
                        .append(Component.text(SiegeMessages.clock(secondsLeft),
                                secondsLeft <= net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.FINAL_COUNTDOWN_SECONDS
                                        ? NamedTextColor.RED : NamedTextColor.WHITE)));
            }
            int personalAt = lines.size(); // #866: a member's own line goes after the clock
            lines.add(Component.empty());
            for (ObjectiveState state : objectives) {
                boolean ours = match.alliances().knows(state.holderTeamId())
                        && match.alliances().allianceOf(state.holderTeamId()) == viewerAlliance;
                String name = SiegeDisplayText.clean(state.objective().name(), "#" + state.objectiveId());
                Component line = Component.text(state.objective().instantVictory() ? "⚑ " : "• ", NamedTextColor.GOLD)
                        .append(Component.text(name + " " + state.capturePercent() + "%", ours ? NamedTextColor.GREEN : NamedTextColor.RED));
                if (state.isContested()) line = line.append(Component.text(" ⚔", NamedTextColor.YELLOW));
                lines.add(line);
            }
            render(board, lines);
            renderPersonal(match, viewerTeam.id(), lines, personalAt);
        }

        // #163: [kills/deaths] next to each member in the tab list, on the siege boards only.
        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            for (Scoreboard board : allBoards(match, boards)) {
                Objective tab = board.getObjective(TAB_OBJECTIVE);
                if (tab != null) showKillsDeaths(tab.getScore(p.getName()), member.kills(), member.deaths());
            }
        }
    }

    /** #866: each member of the team on their own board: the team's lines with their personal line inserted. */
    private void renderPersonal(SiegeMatch match, int teamId, List<Component> teamLines, int at) {
        Map<UUID, Scoreboard> personal = playerBoardsByMatch.get(match.matchToken());
        if (personal == null || personal.isEmpty()) return;
        for (UUID playerId : match.roster().membersOf(teamId)) {
            Scoreboard board = personal.get(playerId);
            if (board == null) continue;
            render(board, withPersonalLine(teamLines, at, personalLine.line(match, playerId)));
        }
    }

    /** The team's lines with {@code personal} at {@code at}; the team's lines unchanged without one. */
    static List<Component> withPersonalLine(List<Component> teamLines, int at, Component personal) {
        if (personal == null) return teamLines;
        List<Component> lines = new ArrayList<>(teamLines);
        lines.add(Math.max(0, Math.min(at, lines.size())), personal);
        return lines;
    }

    /** The match's team boards, then (#866) its members' own boards. */
    private List<Scoreboard> allBoards(SiegeMatch match, Map<Integer, Scoreboard> boards) {
        List<Scoreboard> all = new ArrayList<>(boards.values());
        Map<UUID, Scoreboard> personal = playerBoardsByMatch.get(match.matchToken());
        if (personal != null) all.addAll(personal.values());
        return all;
    }

    /** The member's board in this match: their own (#866) or their team's; empty when they have none. */
    Optional<Scoreboard> boardOf(SiegeMatch match, UUID playerId) {
        Map<UUID, Scoreboard> personal = playerBoardsByMatch.get(match.matchToken());
        if (personal != null && personal.containsKey(playerId)) return Optional.of(personal.get(playerId));
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards == null) return Optional.empty();
        return match.roster().teamOf(playerId).stream().mapToObj(boards::get).filter(java.util.Objects::nonNull).findFirst();
    }

    /** The tab-list score: kills as the number (for sorting), shown as {@code [k/d]}. */
    static void showKillsDeaths(Score score, int kills, int deaths) {
        if (!score.isScoreSet() || score.getScore() != kills) score.setScore(kills);
        Component shown = Component.text("[" + kills + "/" + deaths + "]", NamedTextColor.GRAY);
        NumberFormat format = NumberFormat.fixed(shown);
        if (!format.equals(score.numberFormat())) score.numberFormat(format);
    }

    /** Fixed entry keys with descending scores; the text is each score's custom name. */
    private static void render(Scoreboard board, List<Component> lines) {
        Objective sidebar = board.getObjective("siege");
        if (sidebar == null) return;
        int count = Math.min(MAX_LINES, lines.size());
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "line" + i;
            if (i < count) {
                Score score = sidebar.getScore(entry);
                score.setScore(count - i);
                score.customName(lines.get(i));
            } else {
                board.resetScores(entry);
            }
        }
    }
}
