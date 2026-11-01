package net.knightsandkings.knk.paper.enchantment.display;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#169 round 2: an invisible invoker's name tag is not shown to same-rank players on the shared
 * board; nobody is re-teamed, no board is swapped, and other teams (siege boards) are left alone.
 */
class InvisibleNameTagTest {

    private final Scoreboard shared = FakeBoards.board();
    private final Player invoker = mock(Player.class);

    @Test
    void theInvokersRankTeamStopsShowingInvisibleMembers_NobodyMoves() {
        Team rank = shared.registerNewTeam("tier_12");
        rank.setCanSeeFriendlyInvisibles(true); // a team registered elsewhere, vanilla default
        rank.addEntry("Sneaky");
        rank.addEntry("Friend");
        Team staff = shared.registerNewTeam("staff");
        staff.setCanSeeFriendlyInvisibles(true);
        when(invoker.getName()).thenReturn("Sneaky");

        new InvisibleNameTag(() -> shared).invisible(invoker);

        assertFalse(rank.canSeeFriendlyInvisibles());
        assertTrue(staff.canSeeFriendlyInvisibles(), "only the invoker's team");
        assertTrue(rank.hasEntry("Sneaky"));
        assertTrue(rank.hasEntry("Friend"));
        assertSame(rank, shared.getEntryTeam("Sneaky"));
        verify(invoker, never()).setScoreboard(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noTeamNoBoardOrABrokenBoardIsHarmless() {
        when(invoker.getName()).thenReturn("Loner");
        assertDoesNotThrow(() -> new InvisibleNameTag(() -> shared).invisible(invoker));
        assertDoesNotThrow(() -> new InvisibleNameTag(() -> null).invisible(invoker));
        assertDoesNotThrow(() -> new InvisibleNameTag(() -> {
            throw new IllegalStateException("no scoreboard manager");
        }).invisible(invoker));
        assertDoesNotThrow(() -> new InvisibleNameTag(() -> shared).invisible(null));
    }
}
