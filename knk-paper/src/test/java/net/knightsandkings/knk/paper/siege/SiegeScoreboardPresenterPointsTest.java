package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.paper.utils.ScoreboardUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.A1;
import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.A2;
import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.D1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Siege shop P18 (PandiO/knk-workspace#866): with siege points the sidebar is one board per member with the team's
 * lines plus their own "Siege points: N"; with siege points off (no personal line) the boards stay one per team and
 * look exactly as before (A10, A12). Uses in-memory boards; nothing global is touched ({@code ScoreboardUtil} is stubbed).
 */
class SiegeScoreboardPresenterPointsTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ScoreboardUtil> scoreboardUtil;
    private SiegePointsTestMatch fixture;
    private final Map<UUID, Long> balances = new java.util.HashMap<>(Map.of(A1, 130L, A2, 40L, D1, 15L));

    @BeforeEach
    void setUp() {
        fixture = new SiegePointsTestMatch(KnkSiegeConfiguration.legacyDefaults());
        bukkit = mockStatic(Bukkit.class);
        ScoreboardManager manager = mock(ScoreboardManager.class);
        when(manager.getNewScoreboard()).thenAnswer(i -> SiegeFakeBoards.board());
        bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> fixture.get(i.getArgument(0)));
        scoreboardUtil = mockStatic(ScoreboardUtil.class);
    }

    @AfterEach
    void tearDown() {
        scoreboardUtil.close();
        bukkit.close();
    }

    private SiegeScoreboardPresenter.PersonalLine points() {
        return (match, playerId) -> Component.text("Siege points: " + balances.getOrDefault(playerId, 0L));
    }

    private static List<String> lines(Scoreboard board) {
        Objective sidebar = board.getObjective("siege");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            if (!sidebar.getScore("line" + i).isScoreSet()) continue;
            lines.add(PlainTextComponentSerializer.plainText().serialize(sidebar.getScore("line" + i).customName()));
        }
        return lines;
    }

    private Scoreboard boardOf(UUID id) {
        return fixture.boards.get(id).get();
    }

    @Test
    void withSiegePointsOffTheBoardsStayOnePerTeamWithoutAPointsLine_A12() {
        SiegeScoreboardPresenter presenter = new SiegeScoreboardPresenter();
        presenter.matchStarted(fixture.lobby, fixture.match);

        assertSame(boardOf(A1), boardOf(A2), "teammates share their team's board");
        assertNotSame(boardOf(A1), boardOf(D1));
        assertEquals(List.of("Team: Attackers", "Time left: 10:00", "", "• Keep 0%"), lines(boardOf(A1)));
        assertTrue(lines(boardOf(D1)).stream().noneMatch(l -> l.startsWith("Siege points")));
    }

    @Test
    void withSiegePointsEveryMemberHasTheTeamLinesPlusTheirOwnBalance_A10() {
        SiegeScoreboardPresenter presenter = new SiegeScoreboardPresenter(points());
        presenter.matchStarted(fixture.lobby, fixture.match);

        assertNotSame(boardOf(A1), boardOf(A2), "one board per member");
        assertEquals(List.of("Team: Attackers", "Time left: 10:00", "Siege points: 130", "", "• Keep 0%"), lines(boardOf(A1)));
        assertEquals(List.of("Team: Attackers", "Time left: 10:00", "Siege points: 40", "", "• Keep 0%"), lines(boardOf(A2)));
        assertEquals(List.of("Team: Defenders", "Time left: 10:00", "Siege points: 15", "", "• Keep 0%"), lines(boardOf(D1)));

        // Name-tag teams on every member's own board, as on the team boards (P18 risk § 9.3).
        Scoreboard own = boardOf(A1);
        assertEquals(java.util.Set.of("Alice", "Anna"), own.getTeam("siege_2").getEntries());
        assertEquals(java.util.Set.of("Dave"), own.getTeam("siege_1").getEntries());

        balances.put(A1, 250L);
        presenter.secondTicked(fixture.lobby, fixture.match, null, Map.of());
        assertEquals("Siege points: 250", lines(boardOf(A1)).get(2));
    }

    @Test
    void theTeamLinesAreTheSameWithAndWithoutThePersonalLine() {
        SiegeScoreboardPresenter off = new SiegeScoreboardPresenter();
        off.matchStarted(fixture.lobby, fixture.match);
        List<String> teamLines = lines(boardOf(A1));

        SiegeScoreboardPresenter on = new SiegeScoreboardPresenter(points());
        on.matchStarted(fixture.lobby, fixture.match);
        List<String> personal = new ArrayList<>(lines(boardOf(A1)));
        personal.remove("Siege points: 130");
        assertEquals(teamLines, personal);
    }

    @Test
    void aLeaverGetsTheirPreviousBoardBackAndLeavesEveryOtherBoard() {
        Scoreboard before = boardOf(A2);
        SiegeScoreboardPresenter presenter = new SiegeScoreboardPresenter(points());
        presenter.matchStarted(fixture.lobby, fixture.match);
        Scoreboard aliceBoard = boardOf(A1);

        Player anna = fixture.get(A2);
        presenter.memberRemoved(fixture.lobby, fixture.match, anna);

        assertSame(before, boardOf(A2));
        assertEquals(java.util.Set.of("Alice"), aliceBoard.getTeam("siege_2").getEntries());
        assertSame(aliceBoard, presenter.boardOf(fixture.match, A1).orElseThrow());
    }

    @Test
    void withPersonalLineInsertsAtThePositionAndKeepsTheTeamLinesWithoutOne() {
        List<Component> team = List.of(Component.text("a"), Component.text("b"));
        assertEquals(List.of(Component.text("a"), Component.text("x"), Component.text("b")),
                SiegeScoreboardPresenter.withPersonalLine(team, 1, Component.text("x")));
        assertSame(team, SiegeScoreboardPresenter.withPersonalLine(team, 1, null));
    }
}
