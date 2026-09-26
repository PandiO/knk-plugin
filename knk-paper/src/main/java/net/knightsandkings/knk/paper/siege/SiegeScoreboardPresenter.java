package net.knightsandkings.knk.paper.siege;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
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
 * contested. Every siege team is a Bukkit team on each board, so name tags carry team colours. The
 * tab list shows {@code [Team] name [K/D]} through {@link Player#playerListName(Component)}.
 * <p>
 * The player's previous scoreboard (normally {@code ScoreboardUtil}'s shared one) and tab name are
 * restored when they leave the match or it ends.
 */
public final class SiegeScoreboardPresenter implements SiegeMatchObserver {

    private static final int MAX_LINES = 15;

    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Map<String, Map<Integer, Scoreboard>> boardsByMatch = new HashMap<>();
    private final Map<UUID, String> lastTabName = new HashMap<>();

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
            boards.put(viewerTeam.id(), board);
        }
        boardsByMatch.put(match.matchToken(), boards);

        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            boards.values().forEach(b -> b.getTeam("siege_" + member.teamId()).addEntry(p.getName()));
            previousBoards.putIfAbsent(p.getUniqueId(), p.getScoreboard());
            Scoreboard own = boards.get(member.teamId());
            if (own != null) p.setScoreboard(own);
        }
        refresh(lobby, match);
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

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        for (UUID id : match.roster().playerIds()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        boardsByMatch.remove(match.matchToken());
    }

    @Override
    public void shutdown() {
        for (UUID id : new ArrayList<>(previousBoards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        previousBoards.clear();
        boardsByMatch.clear();
    }

    private void restore(Player player) {
        Scoreboard previous = previousBoards.remove(player.getUniqueId());
        if (previous != null) player.setScoreboard(previous);
        if (lastTabName.remove(player.getUniqueId()) != null) player.playerListName(null);
    }

    // ==================== Rendering ====================

    private void refresh(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Map<Integer, Scoreboard> boards = boardsByMatch.get(match.matchToken());
        if (boards == null) return;
        int secondsLeft = lobby.machine().secondsRemaining();
        List<ObjectiveState> objectives = new ArrayList<>(match.board().objectives());
        objectives.sort(Comparator.comparing((ObjectiveState s) -> !s.objective().instantVictory()));

        for (KnkSiegeTeam viewerTeam : match.scenario().teams()) {
            Scoreboard board = boards.get(viewerTeam.id());
            if (board == null) continue;
            int viewerAlliance = viewerTeam.allianceGroup();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("Team: ", NamedTextColor.GRAY).append(SiegeBukkit.teamComponent(viewerTeam)));
            lines.add(Component.text("Time left: ", NamedTextColor.GRAY)
                    .append(Component.text(SiegeMessages.clock(secondsLeft), NamedTextColor.WHITE)));
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

        for (MemberView member : match.roster().members()) {
            Player p = Bukkit.getPlayer(member.playerId());
            if (p == null) continue;
            KnkSiegeTeam team = match.scenario().team(member.teamId()).orElse(null);
            String key = member.teamId() + ":" + member.kills() + ":" + member.deaths();
            if (key.equals(lastTabName.get(p.getUniqueId()))) continue;
            p.playerListName(Component.text("[" + SiegeBukkit.teamName(team) + "] ", SiegeBukkit.color(team))
                    .append(Component.text(p.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" [" + member.kills() + "/" + member.deaths() + "]", NamedTextColor.GRAY)));
            lastTabName.put(p.getUniqueId(), key);
        }
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
