package net.knightsandkings.knk.paper.gates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit tests for GateStateSyncTask.selectRoundRobinBatch - the pure round-robin batch selection
 * behind Mechanism C's periodic health-check (GATE_WORLD_SYNC_DESIGN.md). Pure int-array math,
 * no Bukkit/GateManager coupling, so it's tested directly rather than via the full periodic task.
 */
class GateStateSyncTaskRoundRobinTest {

    @Test
    void selectRoundRobinBatch_SmallerThanTotal_ReturnsConsecutiveIndicesFromCursor() {
        int[] batch = GateStateSyncTask.selectRoundRobinBatch(10, 2, 3);
        assertArrayEquals(new int[]{2, 3, 4}, batch);
    }

    @Test
    void selectRoundRobinBatch_WrapsAroundTheEndOfTheList() {
        int[] batch = GateStateSyncTask.selectRoundRobinBatch(5, 3, 4);
        assertArrayEquals(new int[]{3, 4, 0, 1}, batch);
    }

    @Test
    void selectRoundRobinBatch_BatchSizeLargerThanTotal_ReturnsEveryIndexOnce() {
        int[] batch = GateStateSyncTask.selectRoundRobinBatch(3, 0, 20);
        assertArrayEquals(new int[]{0, 1, 2}, batch);
    }

    @Test
    void selectRoundRobinBatch_CursorAtExactEnd_WrapsToStart() {
        int[] batch = GateStateSyncTask.selectRoundRobinBatch(5, 5, 2);
        assertArrayEquals(new int[]{0, 1}, batch);
    }

    @Test
    void selectRoundRobinBatch_NegativeCursor_NormalizesIntoRange() {
        // Defensive: a cursor should never actually go negative in practice, but the modulo
        // arithmetic must not produce a negative array index if it ever does.
        int[] batch = GateStateSyncTask.selectRoundRobinBatch(5, -2, 2);
        assertArrayEquals(new int[]{3, 4}, batch);
    }

    @Test
    void selectRoundRobinBatch_ZeroTotal_ReturnsEmpty() {
        assertArrayEquals(new int[0], GateStateSyncTask.selectRoundRobinBatch(0, 0, 5));
    }

    @Test
    void selectRoundRobinBatch_FullSweepAcrossManyRunsCoversEveryIndexExactlyOnce() {
        int total = 23;
        int batchSize = 4;
        int cursor = 0;
        boolean[] visited = new boolean[total];
        int visitedCount = 0;

        // Enough runs to guarantee at least one full wrap-around pass.
        for (int run = 0; run < 10; run++) {
            int[] batch = GateStateSyncTask.selectRoundRobinBatch(total, cursor, batchSize);
            for (int index : batch) {
                if (!visited[index]) {
                    visited[index] = true;
                    visitedCount++;
                }
            }
            cursor = (batch[batch.length - 1] + 1) % total;
        }

        for (int i = 0; i < total; i++) {
            org.junit.jupiter.api.Assertions.assertTrue(visited[i], "Index " + i + " was never visited");
        }
        org.junit.jupiter.api.Assertions.assertEquals(total, visitedCount);
    }
}
