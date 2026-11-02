package net.knightsandkings.knk.core.crossplay;

import net.knightsandkings.knk.core.crossplay.BlockResyncWatch.Pos;
import net.knightsandkings.knk.core.crossplay.BlockResyncWatch.Resend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#161: a refused break is sent again while the Bedrock player keeps mining. */
class BlockResyncWatchTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final Pos wall = new Pos("world", 1, 64, 1);
    private final Pos door = new Pos("world", 2, 64, 1);

    private static int countUpTo(BlockResyncWatch watch, long from, long to) {
        int n = 0;
        for (long t = from; t <= to; t++) {
            n += watch.due(t).size();
        }
        return n;
    }

    @Test
    void theNextTickResendsThenEveryInterval() {
        BlockResyncWatch watch = new BlockResyncWatch(40, 5, 8);
        watch.refused(alice, wall, 100);
        assertTrue(watch.due(100).isEmpty());
        assertEquals(List.of(new Resend(alice, wall)), watch.due(101));
        assertTrue(watch.due(102).isEmpty());
        assertEquals(1, watch.due(106).size());
    }

    @Test
    void theWatchEndsWithALastResendAfterTheWindow() {
        BlockResyncWatch watch = new BlockResyncWatch(10, 5, 8);
        watch.refused(alice, wall, 0);
        // ticks 1, 6, and the final one at 10
        assertEquals(3, countUpTo(watch, 1, 10));
        assertFalse(watch.isWatching(alice));
        assertEquals(0, countUpTo(watch, 11, 50));
    }

    @Test
    void swingingKeepsTheBlockWatched() {
        BlockResyncWatch watch = new BlockResyncWatch(10, 5, 8);
        watch.refused(alice, wall, 0);
        countUpTo(watch, 1, 8);
        watch.swung(alice, 8);
        countUpTo(watch, 9, 17);
        assertTrue(watch.isWatching(alice), "a swing at 8 keeps it until 18");
        assertEquals(1, watch.due(18).size());
        assertFalse(watch.isWatching(alice));
    }

    @Test
    void playersAreWatchedApart() {
        BlockResyncWatch watch = new BlockResyncWatch(10, 5, 8);
        watch.refused(alice, wall, 0);
        watch.refused(bob, door, 0);
        assertEquals(2, watch.due(1).size());
        watch.forget(alice);
        assertEquals(List.of(new Resend(bob, door)), watch.due(6));
    }

    @Test
    void aRefusalAgainDoesNotDoubleTheBlock() {
        BlockResyncWatch watch = new BlockResyncWatch(10, 5, 8);
        watch.refused(alice, wall, 0);
        watch.refused(alice, wall, 3);
        assertEquals(1, watch.due(4).size());
    }

    @Test
    void theOldestBlockGivesWayAtTheCap() {
        BlockResyncWatch watch = new BlockResyncWatch(10, 5, 2);
        watch.refused(alice, wall, 0);
        watch.refused(alice, door, 0);
        Pos third = new Pos("world", 3, 64, 1);
        watch.refused(alice, third, 0);
        List<Resend> due = watch.due(1);
        assertEquals(List.of(new Resend(alice, door), new Resend(alice, third)), due);
    }

    @Test
    void badSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new BlockResyncWatch(0, 5, 8));
    }
}
