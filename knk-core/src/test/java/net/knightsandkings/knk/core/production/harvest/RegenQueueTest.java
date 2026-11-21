package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#134 (PRODUCTION_KINDS § 7): harvested blocks come back after their time, only where the placeholder
 * still stands, never into a player, only with their chunks loaded, across a restart (saved and loaded again) and within
 * the per-tick budget; surveys see the original meanwhile (L12).
 */
class RegenQueueTest {

    private final FakeRegenWorld world = new FakeRegenWorld();
    private final RegenQueue queue = new RegenQueue(5000);

    private RegenJob stoneJob(String id, int x, long restoreAt) {
        world.put(x, 64, 3, "minecraft:bedrock");
        return new RegenJob(id, "world", 0, restoreAt, "entry-" + id,
                List.of(new RegenJob.Block(x, 64, 3, "minecraft:stone", "minecraft:bedrock")));
    }

    @Test
    void aDueJobIsRestoredWhereThePlaceholderStands() {
        queue.add(stoneJob("a", 3, 60_000));
        assertEquals(RegenQueue.TickResult.NONE, queue.tick(world, 59_999, 64));
        assertEquals("minecraft:bedrock", world.at(3, 64, 3));
        assertEquals(1, queue.tick(world, 60_000, 64).restored());
        assertEquals("minecraft:stone", world.at(3, 64, 3));
        assertEquals(0, queue.size());
        assertFalse(queue.regenerating("world", 3, 64, 3));
    }

    @Test
    void aChangedBlockIsNeverOverwritten() {
        queue.add(stoneJob("a", 3, 1000));
        world.put(3, 64, 3, "minecraft:chest[facing=north]"); // someone built there
        RegenQueue.TickResult result = queue.tick(world, 1000, 64);
        assertEquals(1, result.dropped());
        assertEquals("minecraft:chest[facing=north]", world.at(3, 64, 3));
        assertEquals(0, queue.size());
    }

    @Test
    void aPlayerInTheBlockPostponesTheRestore() {
        queue.add(stoneJob("a", 3, 1000));
        world.occupied.add(FakeRegenWorld.key(3, 64, 3));
        assertEquals(1, queue.tick(world, 1000, 64).postponed());
        assertEquals("minecraft:bedrock", world.at(3, 64, 3));
        assertEquals(RegenQueue.TickResult.NONE, queue.tick(world, 5999, 64));
        world.occupied.clear();
        assertEquals(1, queue.tick(world, 6000, 64).restored());
        assertEquals("minecraft:stone", world.at(3, 64, 3));
    }

    @Test
    void anUnloadedChunkWaitsUntilItLoads() {
        queue.add(stoneJob("a", 3, 1000));
        world.unloaded.add(HarvestAreaIndex.chunkKey(0, 0));
        assertEquals(1, queue.tick(world, 1000, 64).waiting());
        assertEquals(1, queue.waitingCount());
        assertEquals(RegenQueue.TickResult.NONE, queue.tick(world, 50_000, 64)); // nothing to do until the chunk loads
        assertEquals(0, world.sets);
        world.unloaded.clear();
        queue.chunkLoaded("world", 0, 0, 60_000);
        assertEquals(1, queue.tick(world, 60_000, 64).restored());
        assertEquals("minecraft:stone", world.at(3, 64, 3));
        assertEquals(0, queue.waitingCount());
    }

    @Test
    void aFelledTreeComesBackWholeOnlyWhenEveryChunkIsLoaded() {
        world.put(15, 64, 0, "minecraft:oak_sapling[stage=0]");
        world.put(16, 65, 0, "minecraft:air");
        RegenJob tree = new RegenJob("t", "world", 0, 1000, null, List.of(
                new RegenJob.Block(15, 64, 0, "minecraft:oak_log[axis=y]", "minecraft:oak_sapling[stage=0]"),
                new RegenJob.Block(16, 65, 0, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]",
                        "minecraft:air")));
        queue.add(tree);
        world.unloaded.add(HarvestAreaIndex.chunkKey(1, 0));
        assertEquals(1, queue.tick(world, 1000, 64).waiting());
        world.unloaded.clear();
        queue.chunkLoaded("world", 1, 0, 2000);
        assertEquals(1, queue.tick(world, 2000, 64).restored());
        assertEquals("minecraft:oak_log[axis=y]", world.at(15, 64, 0));
        assertTrue(world.at(16, 65, 0).startsWith("minecraft:oak_leaves"));
    }

    @Test
    void aSaplingThatGrewDoesNotStopTheRestoreButATakenSpotDoes() {
        world.put(1, 64, 1, "minecraft:oak_sapling[stage=1]"); // random ticks raise the stage: same material
        queue.add(new RegenJob("s", "world", 0, 0, null,
                List.of(new RegenJob.Block(1, 64, 1, "minecraft:oak_log[axis=y]", "minecraft:oak_sapling[stage=0]"))));
        assertEquals(1, queue.tick(world, 0, 64).restored());
    }

    @Test
    void jobsSurviveARestart() {
        queue.add(stoneJob("a", 3, 60_000));
        queue.add(stoneJob("b", 4, 120_000));
        assertTrue(queue.dirty());
        List<String> saved = queue.encode();
        assertFalse(queue.dirty());

        RegenQueue after = new RegenQueue(5000);
        List<String> logged = new ArrayList<>();
        after.setLog(logged::add);
        List<String> lines = new ArrayList<>(saved);
        lines.add("not a job");
        assertEquals(2, after.load(lines));
        assertEquals(1, logged.size());
        assertEquals("STONE", after.overlay().originalAt("world", 4, 64, 3));
        assertEquals(1, after.tick(world, 60_000, 64).restored());
        assertEquals("minecraft:bedrock", world.at(4, 64, 3));
        assertEquals(1, after.tick(world, 120_000, 64).restored());
        assertEquals("minecraft:stone", world.at(4, 64, 3));
    }

    @Test
    void theApiAnswerSetsTheRegenerationTime() {
        queue.add(stoneJob("a", 3, 60_000));
        queue.setRegenSeconds("entry-a", 120); // the pool is low: the API doubled it
        assertEquals(RegenQueue.TickResult.NONE, queue.tick(world, 60_000, 64));
        assertEquals(1, queue.tick(world, 120_000, 64).restored());
        queue.add(stoneJob("b", 4, 60_000));
        queue.setRegenSeconds("entry-b", 10);
        assertEquals(1, queue.tick(world, 10_000, 64).restored());
    }

    @Test
    void theTickBudgetLimitsTheBlocksRestored() {
        for (int i = 0; i < 5; i++) {
            queue.add(stoneJob("j" + i, i, 1000));
        }
        assertEquals(2, queue.tick(world, 1000, 2).restored());
        assertEquals(3, queue.size());
        assertEquals(3, queue.tick(world, 1000, 64).restored());
    }

    @Test
    void surveysSeeTheOriginalMeanwhile() {
        queue.add(stoneJob("a", 3, 60_000));
        assertEquals("STONE", queue.overlay().originalAt("world", 3, 64, 3));
        assertNull(queue.overlay().originalAt("world", 3, 65, 3));
        assertNull(queue.overlay().originalAt("nether", 3, 64, 3));
        assertTrue(queue.regenerating("world", 3, 64, 3));
    }

    @Test
    void brokenJobLinesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RegenJob.decode("a\tworld\t0\t1\t-"));
        assertThrows(IllegalArgumentException.class, () -> RegenJob.decode("a\tworld\tx\t1\t-\t1 2 3 a b"));
        RegenJob job = RegenJob.decode(stoneJob("z", 1, 5).encode());
        assertEquals("entry-z", job.entryKey());
        assertEquals("minecraft:stone", job.blocks().get(0).original());
    }
}
