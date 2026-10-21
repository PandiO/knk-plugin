package net.knightsandkings.knk.core.duel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaQueueTest {
    private final BattlegroundAllocator allocator = new BattlegroundAllocator(List.of(1, 2));
    private final ArenaQueue queue = new ArenaQueue(allocator, 3, 180);

    @Test
    void theCapRefusesTheFourthAccept() {
        assertTrue(queue.admit("a"));
        assertTrue(queue.admit("b"));
        assertTrue(queue.hasRoom());
        assertTrue(queue.admit("c"));
        assertFalse(queue.hasRoom());
        assertFalse(queue.admit("d"));
        assertFalse(queue.contains("d"));
        assertTrue(queue.admit("a"), "admitting twice is a no-op");
        queue.remove("b");
        assertTrue(queue.admit("d"));
        assertEquals(3, queue.queuedCount());
    }

    @Test
    void aMatchOnABattlegroundNoLongerCounts() {
        queue.admit("a");
        queue.admit("b");
        queue.admit("c");
        queue.readyForBattleground("a", 0);
        ArenaQueue.Update update = queue.advance(0);
        assertEquals(List.of(new ArenaQueue.Assignment("a", 1)), update.assigned());
        assertTrue(queue.hasRoom());
        assertEquals(1, allocator.busyCount());
    }

    @Test
    void battlegroundsAreHandedOutFirstReadyFirstServed() {
        for (String t : List.of("a", "b", "c")) queue.admit(t);
        queue.readyForBattleground("c", 10);
        queue.readyForBattleground("a", 20);
        queue.readyForBattleground("b", 30);
        assertEquals(1, queue.positionOf("c"));
        assertEquals(3, queue.positionOf("b"));
        ArenaQueue.Update update = queue.advance(40);
        assertEquals(List.of(new ArenaQueue.Assignment("c", 1), new ArenaQueue.Assignment("a", 2)), update.assigned());
        assertEquals(1, queue.positionOf("b"));
        assertEquals("c", allocator.matchOn(1));
        assertEquals(1, queue.releaseBattleground("c"));
        assertEquals(List.of(new ArenaQueue.Assignment("b", 1)), queue.advance(41).assigned());
    }

    @Test
    void aMatchStillInWarmupIsNotServed() {
        queue.admit("a");
        assertTrue(queue.advance(1000).isEmpty());
        assertEquals(0, queue.positionOf("a"));
    }

    @Test
    void theWaitTimesOutAfterMaxWaitSeconds() {
        BattlegroundAllocator one = new BattlegroundAllocator(List.of(1));
        ArenaQueue q = new ArenaQueue(one, 3, 180);
        q.admit("a");
        q.admit("b");
        q.readyForBattleground("a", 0);
        q.readyForBattleground("b", 100);
        assertEquals(List.of(new ArenaQueue.Assignment("a", 1)), q.advance(100).assigned());
        long limit = 100 + DuelTicks.ofSeconds(180);
        assertTrue(q.advance(limit - 1).isEmpty());
        ArenaQueue.Update update = q.advance(limit);
        assertEquals(List.of("b"), update.timedOut());
        assertFalse(q.contains("b"));
    }

    @Test
    void aFreeBattlegroundBeatsTheTimeout() {
        BattlegroundAllocator one = new BattlegroundAllocator(List.of(1));
        ArenaQueue q = new ArenaQueue(one, 3, 0);
        q.admit("a");
        q.readyForBattleground("a", 0);
        assertEquals(1, q.advance(0).assigned().size());
    }

    @Test
    void refreshKeepsBusyBattlegroundsUntilReleased() {
        assertEquals(1, allocator.allocate("x").orElseThrow());
        allocator.setBattlegrounds(List.of(2, 3));
        assertEquals(2, allocator.freeCount());
        assertEquals(1, allocator.busyCount());
        assertEquals(2, allocator.allocate("y").orElseThrow());
        assertEquals(1, allocator.release("x"));
        assertNull(allocator.release("x"));
        assertEquals(1, allocator.freeCount());
        assertEquals(2, allocator.totalCount());
    }

    @Test
    void allocatingTwiceReturnsTheSameBattleground() {
        assertEquals(1, allocator.allocate("x").orElseThrow());
        assertEquals(1, allocator.allocate("x").orElseThrow());
        assertEquals(2, allocator.allocate("y").orElseThrow());
        assertTrue(allocator.allocate("z").isEmpty());
    }

    @Test
    void endToEndWithTheStateMachine() {
        DuelStateMachine machine = new DuelStateMachine(new DuelTimings(30, 10, 300, 5, 15), GearMode.OWN_GEAR_KEEP, null, List.of());
        assertTrue(queue.admit("m"));
        machine.lockSucceeded(0);
        machine.travelCompleted(0);
        List<DuelEffect> effects = machine.advance(DuelTicks.ofSeconds(30));
        assertTrue(DuelTestSupport.has(effects, DuelEffect.AwaitBattleground.class));
        queue.readyForBattleground("m", DuelTicks.ofSeconds(30));
        ArenaQueue.Assignment a = queue.advance(DuelTicks.ofSeconds(30)).assigned().getFirst();
        machine.battlegroundAssigned(a.battlegroundId(), DuelTicks.ofSeconds(30));
        assertEquals(DuelPhase.BATTLE_COUNTDOWN, machine.phase());
        assertEquals(1, machine.battlegroundId());
    }
}
