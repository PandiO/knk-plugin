package net.knightsandkings.knk.paper.enchantment.display;

import net.knightsandkings.knk.paper.utils.ViewerScoreboards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#169: a blinded player (round 1: the flash_chaos victim) sees a mirror of their own board with every name tag hidden; nobody
 * is re-teamed on the real boards, and the real board comes back on the end, death, quit or plugin disable, also when
 * a siege match swapped it meanwhile.
 */
class NameTagBlackoutTest {

    private final AtomicLong now = new AtomicLong(0L);
    private final List<Player> online = new ArrayList<>();
    /** Registry-backed in the real API; the fake board only stores it. */
    private final Criteria dummy = null;
    private NameTagBlackout blackout;
    private MockedStatic<Bukkit> bukkit;

    private Scoreboard rankBoard;
    private Player viewer;
    private Player rankMember;
    private Player loner;

    @BeforeEach
    void setUp() {
        blackout = new NameTagBlackout(FakeBoards::board, () -> online, now::get);
        ViewerScoreboards.setOverlay(blackout);
        bukkit = mockStatic(Bukkit.class);

        rankBoard = FakeBoards.board();
        Team staff = rankBoard.registerNewTeam("staff");
        staff.color(NamedTextColor.BLUE);
        staff.prefix(Component.text("[Staff] "));
        viewer = player("Viewer", rankBoard);
        rankMember = player("Alice", rankBoard);
        loner = player("Bob", rankBoard);
        staff.addEntry("Alice");
        staff.addEntry("Viewer");
        Objective health = rankBoard.registerNewObjective("Health", dummy, Component.text("❤"), RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.BELOW_NAME);
        health.getScore("Alice").setScore(20);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        ViewerScoreboards.setOverlay(null);
    }

    @Test
    void theVictimSeesAMirrorWithEveryNameTagHidden_TheRealBoardIsUntouched() {
        blackout.hide(viewer, 100);

        Scoreboard shown = viewer.getScoreboard();
        assertNotSame(rankBoard, shown);
        Team mirrored = shown.getTeam("staff");
        assertEquals(Team.OptionStatus.NEVER, mirrored.getOption(Team.Option.NAME_TAG_VISIBILITY));
        assertEquals(NamedTextColor.BLUE, mirrored.color());
        assertEquals(Component.text("[Staff] "), mirrored.prefix());
        assertTrue(mirrored.hasEntry("Alice"));
        // A player on no team gets the catch-all team, also with hidden tags.
        Team catchAll = shown.getTeam(NameTagBlackout.CATCH_ALL_TEAM);
        assertTrue(catchAll.hasEntry("Bob"));
        assertFalse(catchAll.hasEntry("Alice"));
        assertEquals(Team.OptionStatus.NEVER, catchAll.getOption(Team.Option.NAME_TAG_VISIBILITY));
        // The below-name health keeps showing.
        assertEquals(20, shown.getObjective(DisplaySlot.BELOW_NAME).getScore("Alice").getScore());

        // Nobody else's board changed.
        assertSame(rankBoard, rankMember.getScoreboard());
        assertNull(rankBoard.getTeam(NameTagBlackout.CATCH_ALL_TEAM));
        assertEquals(Team.OptionStatus.ALWAYS, rankBoard.getTeam("staff").getOption(Team.Option.NAME_TAG_VISIBILITY));
        assertTrue(blackout.isHidden(viewer.getUniqueId()));
    }

    @Test
    void theRealBoardComesBackWhenTheBlackoutRunsOut_AndAnOverlapExtendsIt() {
        bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        blackout.hide(viewer, 100); // 5 s
        Scoreboard mirror = viewer.getScoreboard();
        now.set(3_000L);
        blackout.hide(viewer, 100); // a second flash: until 8 s, same mirror
        assertSame(mirror, viewer.getScoreboard());

        now.set(6_000L);
        blackout.tick();
        assertSame(mirror, viewer.getScoreboard());

        now.set(8_000L);
        blackout.tick();
        assertSame(rankBoard, viewer.getScoreboard());
        assertFalse(blackout.isHidden(viewer.getUniqueId()));
    }

    /** Round 2: the blackout follows the blindness, so milk (blindness gone) ends it before its time. */
    @Test
    void theBlackoutEndsEarlyWhenTheBlindnessIsGone() {
        bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        boolean[] blind = {true};
        blackout.hide(viewer, 200, p -> blind[0]); // 10 s
        Scoreboard mirror = viewer.getScoreboard();

        now.set(1_000L);
        blackout.tick();
        assertSame(mirror, viewer.getScoreboard());

        blind[0] = false; // milk
        now.set(2_000L);
        blackout.tick();
        assertSame(rankBoard, viewer.getScoreboard());
        assertFalse(blackout.isHidden(viewer.getUniqueId()));

        // A new blindness later starts a fresh blackout with its own check.
        blind[0] = true;
        blackout.hide(viewer, 200, p -> blind[0]);
        assertTrue(blackout.isHidden(viewer.getUniqueId()));
    }

    @Test
    void aSecondBlindnessExtendsTheBlackout_WithoutACheckOnlyTheTimeCounts() {
        bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        blackout.hide(viewer, 100);               // 5 s, no check
        now.set(2_000L);
        blackout.hide(viewer, 200, p -> true);    // until 12 s
        now.set(11_000L);
        blackout.tick();
        assertTrue(blackout.isHidden(viewer.getUniqueId()));
        now.set(12_000L);
        blackout.tick();
        assertSame(rankBoard, viewer.getScoreboard());
    }

    @Test
    void deathAndQuitHandTheRealBoardBack() {
        blackout.hide(viewer, 200);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(viewer);
        blackout.onDeath(death);
        assertSame(rankBoard, viewer.getScoreboard());

        blackout.hide(viewer, 200);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(viewer);
        blackout.onQuit(quit);
        assertSame(rankBoard, viewer.getScoreboard());
        assertFalse(blackout.isHidden(viewer.getUniqueId()));
    }

    /** A siege match starts and ends during the blackout: the board owners go through ViewerScoreboards. */
    @Test
    void aBoardSwapDuringTheBlackoutLandsBehindTheMirror_AndIsWhatComesBack() {
        Scoreboard siegeBoard = FakeBoards.board();
        Team red = siegeBoard.registerNewTeam("siege_1");
        red.color(NamedTextColor.RED);
        red.addEntry("Viewer");
        red.addEntry("Alice");
        Objective sidebar = siegeBoard.registerNewObjective("siege", dummy, Component.text("Siege"), RenderType.INTEGER);
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebar.getScore("line0").setScore(2);
        sidebar.getScore("line0").customName(Component.text("Time left: 5:00"));

        blackout.hide(viewer, 200);
        Scoreboard mirror = viewer.getScoreboard();

        // SiegeScoreboardPresenter.show: remembers the real board, then sets the team board.
        assertSame(rankBoard, ViewerScoreboards.current(viewer));
        ViewerScoreboards.set(viewer, siegeBoard);
        assertSame(mirror, viewer.getScoreboard());
        assertSame(siegeBoard, ViewerScoreboards.current(viewer));
        assertNull(mirror.getTeam("staff"));
        assertEquals(Team.OptionStatus.NEVER, mirror.getTeam("siege_1").getOption(Team.Option.NAME_TAG_VISIBILITY));
        assertEquals(NamedTextColor.RED, mirror.getTeam("siege_1").color());
        assertNull(mirror.getObjective(DisplaySlot.BELOW_NAME));
        Objective mirroredSidebar = mirror.getObjective(DisplaySlot.SIDEBAR);
        assertEquals(Component.text("Time left: 5:00"), mirroredSidebar.getScore("line0").customName());

        // The siege sidebar ticks; the mirror follows on the next second.
        sidebar.getScore("line0").customName(Component.text("Time left: 4:59"));
        bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        now.set(1_000L);
        blackout.tick();
        assertEquals(Component.text("Time left: 4:59"), mirroredSidebar.getScore("line0").customName());

        blackout.show(viewer);
        assertSame(siegeBoard, viewer.getScoreboard());
        assertEquals(Team.OptionStatus.ALWAYS, red.getOption(Team.Option.NAME_TAG_VISIBILITY));
    }

    @Test
    void withoutABlackoutViewerScoreboardsSetsTheBoardDirectly() {
        Scoreboard other = FakeBoards.board();
        ViewerScoreboards.set(viewer, other);
        assertSame(other, viewer.getScoreboard());
        assertSame(other, ViewerScoreboards.current(viewer));
    }

    @Test
    void showAllOnDisableHandsEveryBoardBack() {
        bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        blackout.hide(viewer, 200);
        blackout.showAll();
        assertSame(rankBoard, viewer.getScoreboard());
    }

    /** A player whose board is a plain field, like the server keeps it. */
    private Player player(String name, Scoreboard board) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        Scoreboard[] current = {board};
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getScoreboard()).thenAnswer(inv -> current[0]);
        org.mockito.Mockito.doAnswer(inv -> {
            current[0] = inv.getArgument(0);
            return null;
        }).when(player).setScoreboard(org.mockito.ArgumentMatchers.any());
        online.add(player);
        return player;
    }
}
