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
 */
public final class SiegeScoreboardPresenter implements SiegeMatchObserver {

    private static final int MAX_LINES = 15;

    /** The tab-list objective on the siege boards: each member's {@code [kills/deaths]} (#163). */
    static final String TAB_OBJECTIVE = "siege_kd";

    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Map<String, Map<Integer, Scoreboard>> boardsByMatch = new HashMap<>();
    /** Matches that ended and wait at the hub (#198): their boards show the result. */
    private final Map<String, Boolean> endedMatches = new HashMap<>();

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, Scoreboard> boards = new HashMap<>();
        for (KnkSiegeTeam viewerTeam : match.scenario().teams()) {
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
            boards.put(viewerTeam.id(), board);
        }
        boardsByMatch.put(match.matchToken(), boards);

        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            show(boards, p, member.teamId());
        }
        refresh(lobby, match);
    }

    /** A rejoin or admin join (PandiO/knk-workspace#150, #152): the late member gets their team's board too. */
    @Override
    public void memberJoinedMatch(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards == null) return;
        match.roster().teamOf(player.getUniqueId()).ifPresent(teamId -> show(boards, player, teamId));
        refresh(lobby, match);
    }

    private void show(Map<Integer, Scoreboard> boards, Player p, int teamId) {
        boards.values().forEach(b -> {
            Team team = b.getTeam("siege_" + teamId);
            if (team != null) team.addEntry(p.getName());
        });
        // #169: through a name-tag blackout, so the board kept and handed back is the real one.
        previousBoards.putIfAbsent(p.getUniqueId(), ViewerScoreboards.current(p));
        Scoreboard own = boards.get(teamId);
        if (own != null) ViewerScoreboards.set(p, own);
        previousBoards.putIfAbsent(p.getUniqueId(), boardOf(p));
        Scoreboard own = boards.get(teamId);
        if (own != null) setBoard(p, own);
        ScoreboardUtil.setSiegeTag(p, true);
    }

    // ==================== Board assignment (one place, see the class comment) ====================

    /** Gives the player this board. With package G merged: {@code ViewerScoreboards.set(player, board)}. */
    private static void setBoard(Player player, Scoreboard board) {
        player.setScoreboard(board);
    }

    /** The player's own board. With package G merged: {@code ViewerScoreboards.current(player)}. */
    private static Scoreboard boardOf(Player player) {
        return player.getScoreboard();
    }

    @Override
    public void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step, Map<Integer, List<Presence>> presence) {
        refresh(lobby, match);
    }

    @Override
    public void memberRemoved(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards != null) {
            boards.values().forEach(b -> {
                Team t = b.getEntryTeam(player.getName());
                if (t != null) t.removeEntry(player.getName());
            });
        }
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
        endedMatches.clear();
    }

    private void restore(Player player) {
        Scoreboard previous = previousBoards.remove(player.getUniqueId());
        if (previous != null) ViewerScoreboards.set(player, previous);
        if (lastTabName.remove(player.getUniqueId()) != null) player.playerListName(null);
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
        }

        // #163: [kills/deaths] next to each member in the tab list, on the siege boards only.
        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            for (Scoreboard board : boards.values()) {
                Objective tab = board.getObjective(TAB_OBJECTIVE);
                if (tab != null) showKillsDeaths(tab.getScore(p.getName()), member.kills(), member.deaths());
            }
        }
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
