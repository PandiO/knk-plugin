package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NpcSpawnQueueTest {

    @Test
    void spawnsAFixedNumberPerTickInOrder() {
        NpcSpawnQueue queue = new NpcSpawnQueue(2);
        List<Integer> spawned = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            queue.add(() -> spawned.add(n));
        }

        assertEquals(2, queue.tick());
        assertEquals(List.of(0, 1), spawned);
        assertEquals(3, queue.pending());
        assertEquals(2, queue.tick());
        assertEquals(1, queue.tick());
        assertEquals(0, queue.tick());
        assertEquals(List.of(0, 1, 2, 3, 4), spawned);
    }

    @Test
    void aSpawnThatFailsDoesNotHoldUpTheRest() {
        NpcSpawnQueue queue = new NpcSpawnQueue(3);
        List<String> spawned = new ArrayList<>();
        queue.add(() -> spawned.add("a"));
        queue.add(() -> {
            throw new IllegalStateException("refused");
        });
        queue.add(() -> spawned.add("c"));

        assertThrows(IllegalStateException.class, queue::tick);
        assertEquals(List.of("a", "c"), spawned);
        assertEquals(0, queue.pending());
    }

    @Test
    void clearDropsWhatHasNotSpawned() {
        NpcSpawnQueue queue = new NpcSpawnQueue(1);
        queue.add(() -> { });
        queue.add(() -> { });

        assertEquals(2, queue.clear());
        assertEquals(0, queue.tick());
        assertThrows(IllegalArgumentException.class, () -> new NpcSpawnQueue(0));
    }
}
