package net.knightsandkings.knk.core.effects;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#170: one bar per effect, counting down, static staff freeze. */
class TimedEffectBoardTest {

    private final TimedEffectBoard board = new TimedEffectBoard();
    private final UUID player = UUID.randomUUID();

    @Test
    void aTimedBarCountsDownAndEndsOnItsOwn() {
        board.startTimed(player, "poison", "Poison", 4_000L, 1_000L);

        List<TimedEffectBoard.View> views = board.views(player, 2_000L);
        assertEquals(1, views.size());
        assertEquals("Poison · 3s", views.get(0).text());
        assertEquals(0.75f, views.get(0).progress(), 0.0001f);

        assertTrue(board.views(player, 5_000L).isEmpty());
        assertFalse(board.has(player, "poison"));
        assertTrue(board.players().isEmpty());
    }

    @Test
    void oneBarPerEffect_AndTheSameEffectAgainRestartsItsBar() {
        board.startTimed(player, "poison", "Poison", 4_000L, 0L);
        board.startTimed(player, "freeze", "Frozen", 2_000L, 0L);
        board.startTimed(player, "poison", "Poison", 6_000L, 1_000L);

        List<TimedEffectBoard.View> views = board.views(player, 1_000L);
        assertEquals(List.of("poison", "freeze"), views.stream().map(TimedEffectBoard.View::key).toList());
        assertEquals(6_000L, views.get(0).remainingMs());
        assertEquals(1.0f, views.get(0).progress(), 0.0001f);
    }

    @Test
    void syncFollowsTheRealEffect_ShorterLongerOrGone() {
        board.startTimed(player, "poison", "Poison", 4_000L, 0L);

        board.sync(player, "poison", 1_000L, 1_000L); // milk-like cut
        assertEquals(0.25f, board.views(player, 1_000L).get(0).progress(), 0.0001f);

        board.sync(player, "poison", 10_000L, 1_000L); // topped up elsewhere: the new full bar
        assertEquals(1.0f, board.views(player, 1_000L).get(0).progress(), 0.0001f);

        board.sync(player, "poison", 0L, 2_000L);
        assertFalse(board.has(player, "poison"));
    }

    @Test
    void aStaticBarStaysFull_AndDeathOnlyEndsTheTimedOnes() {
        board.startStatic(player, "staff_freeze", "Frozen");
        board.startTimed(player, "wither", "Wither", 2_000L, 0L);

        List<TimedEffectBoard.View> views = board.views(player, 1_000_000L);
        assertEquals(1, views.size());
        assertEquals("Frozen", views.get(0).text());
        assertEquals(1.0f, views.get(0).progress(), 0.0001f);

        board.startTimed(player, "wither", "Wither", 2_000L, 0L);
        board.endTimed(player);
        assertTrue(board.has(player, "staff_freeze"));
        assertFalse(board.has(player, "wither"));

        board.sync(player, "staff_freeze", 0L, 0L); // static bars ignore sync
        assertTrue(board.has(player, "staff_freeze"));
        board.clear(player);
        assertTrue(board.players().isEmpty());
    }

    @Test
    void noBarForNoDuration() {
        board.startTimed(player, "poison", "Poison", 0L, 0L);
        assertTrue(board.players().isEmpty());
    }

    @Test
    void clockRoundsUpAndShowsMinutes() {
        assertEquals("1s", TimedEffectBoard.clock(1L));
        assertEquals("4s", TimedEffectBoard.clock(3_001L));
        assertEquals("59s", TimedEffectBoard.clock(59_000L));
        assertEquals("1:05", TimedEffectBoard.clock(65_000L));
    }
}
