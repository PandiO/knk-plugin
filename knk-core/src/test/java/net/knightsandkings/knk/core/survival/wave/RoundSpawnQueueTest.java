package net.knightsandkings.knk.core.survival.wave;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §3 steps 3–4, §3c system kills, §7 max alive. */
class RoundSpawnQueueTest {

    static List<PlannedEnemy> planned(int round, int count) {
        List<PlannedEnemy> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(new PlannedEnemy(round * 1000 + i, round, "ZOMBIE", false));
        return list;
    }

    @Test
    void maxAlivePacesSpawnsAndTheRestSpawnsAsOthersDie() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 5), 2);
        assertTrue(queue.nextSpawn(0).isPresent());
        assertTrue(queue.nextSpawn(0).isPresent());
        assertEquals(Optional.empty(), queue.nextSpawn(0), "max alive reached");
        assertEquals(2, queue.aliveCount());
        assertEquals(3, queue.queuedCount());
        assertFalse(queue.canSpawn());

        assertTrue(queue.recordDeath(1001, KillCause.PLAYER));
        assertTrue(queue.canSpawn());
        assertTrue(queue.nextSpawn(10).isPresent());
        assertEquals(2, queue.aliveCount());
        assertEquals(2, queue.queuedCount());
    }

    @Test
    void unlimitedWhenMaxAliveIsZero() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 50), 0);
        for (int i = 0; i < 50; i++) assertTrue(queue.nextSpawn(0).isPresent());
        assertTrue(queue.isQueueExhausted());
        assertEquals(50, queue.aliveCount());
    }

    @Test
    void roundCompletesOnlyWhenQueueExhaustedAndNoEnemyAlive() {
        RoundSpawnQueue queue = new RoundSpawnQueue(2, planned(2, 3), 1);
        assertFalse(queue.isComplete(), "nothing spawned yet is not complete");
        PlannedEnemy first = queue.nextSpawn(0).orElseThrow();
        queue.recordDeath(first.id(), KillCause.PLAYER);
        assertFalse(queue.isComplete(), "combat area empty but queue not exhausted");
        assertEquals(0, queue.aliveCount());
        PlannedEnemy second = queue.nextSpawn(5).orElseThrow();
        queue.recordDeath(second.id(), KillCause.PLAYER);
        PlannedEnemy third = queue.nextSpawn(9).orElseThrow();
        assertTrue(queue.isQueueExhausted());
        assertEquals(Optional.of(9L), queue.lastPlannedSpawnMillis());
        assertFalse(queue.isComplete(), "last enemy still alive");
        queue.recordDeath(third.id(), KillCause.ENVIRONMENT);
        assertTrue(queue.isComplete());
    }

    @Test
    void lastPlannedSpawnMillisIsEmptyUntilExhausted() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 2), 0);
        queue.nextSpawn(3);
        assertEquals(Optional.empty(), queue.lastPlannedSpawnMillis());
        queue.nextSpawn(7);
        assertEquals(Optional.of(7L), queue.lastPlannedSpawnMillis());
    }

    @Test
    void systemKillsCountForCompletionButNotAsEarnedKills() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 4), 0);
        for (int i = 0; i < 4; i++) queue.nextSpawn(0);
        queue.recordDeath(1001, KillCause.PLAYER);
        queue.recordDeath(1002, KillCause.SYSTEM);
        queue.recordDeath(1003, KillCause.NPC);
        queue.recordDeath(1004, KillCause.ENVIRONMENT);
        assertTrue(queue.isComplete());
        assertEquals(1, queue.earnedKills());
        assertEquals(1, queue.systemKills());
        assertEquals(2, queue.otherDeaths());
    }

    @Test
    void deathIsCountedOnceAndUnknownIdsAreIgnored() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 1), 0);
        assertFalse(queue.recordDeath(1001, KillCause.PLAYER), "not spawned yet");
        queue.nextSpawn(0);
        assertTrue(queue.recordDeath(1001, KillCause.PLAYER));
        assertFalse(queue.recordDeath(1001, KillCause.PLAYER), "already dead");
        assertFalse(queue.recordDeath(999, KillCause.SYSTEM));
        assertEquals(1, queue.earnedKills());
    }

    @Test
    void remainingCountsQueuedPlusAlive() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 5), 2);
        queue.nextSpawn(0);
        queue.nextSpawn(0);
        assertEquals(5, queue.remainingCount());
        queue.recordDeath(1001, KillCause.PLAYER);
        assertEquals(4, queue.remainingCount());
    }

    @Test
    void cancelRemainingSpawnsEmptiesTheQueueAndKeepsAlive() {
        RoundSpawnQueue queue = new RoundSpawnQueue(1, planned(1, 3), 0);
        queue.nextSpawn(0);
        List<PlannedEnemy> cancelled = queue.cancelRemainingSpawns();
        assertEquals(2, cancelled.size());
        assertTrue(queue.isQueueExhausted());
        assertEquals(1, queue.aliveCount());
        assertFalse(queue.isComplete());
    }

    @Test
    void rejectsWrongRoundAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new RoundSpawnQueue(2, planned(1, 1), 0));
        List<PlannedEnemy> dup = List.of(new PlannedEnemy(1, 1, "ZOMBIE", false), new PlannedEnemy(1, 1, "SPIDER", false));
        assertThrows(IllegalArgumentException.class, () -> new RoundSpawnQueue(1, dup, 0));
    }
}
