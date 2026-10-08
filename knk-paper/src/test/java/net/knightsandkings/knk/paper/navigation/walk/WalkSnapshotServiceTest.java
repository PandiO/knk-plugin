package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import net.knightsandkings.knk.paper.utils.TickBudget;

/** The main-thread capture (KNG-51 §8): loaded chunks only, tick budget, shared TTL cache. */
class WalkSnapshotServiceTest {

    /** {@link WalkSnapshotService.WorldChunks} over the capture test's fake world, with a loaded set and a read log. */
    static final class FakeChunks implements WalkSnapshotService.WorldChunks {
        final WalkCaptureTest.FakeWorld world;
        final Set<Long> unloaded = new HashSet<>();
        final List<String> captured = new ArrayList<>();

        FakeChunks(WalkCaptureTest.FakeWorld world) {
            this.world = world;
        }

        @Override
        public String name() {
            return "world";
        }

        @Override
        public int minY() {
            return WalkCaptureTest.MIN_Y;
        }

        @Override
        public int maxY() {
            return WalkCaptureTest.MAX_Y;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return !unloaded.contains(WalkChunk.key(chunkX, chunkZ));
        }

        @Override
        public SpanExtractor.BlockSource blocks(int chunkX, int chunkZ) {
            captured.add(chunkX + "," + chunkZ);
            return world.chunk(chunkX, chunkZ);
        }
    }

    private final long[] now = {1_000_000L};
    private final double[] tps = {20.0};
    private final FakeChunks chunks = new FakeChunks(WalkCaptureTest.village());
    private final WalkSnapshotService service = new WalkSnapshotService(WalkCaptureTest.rules(),
        NavigationConfig.WalkConfig.defaults(), new TickBudget(() -> tps[0]), () -> now[0]);

    /** A 2 × 2-chunk box: (2, 64, 2) → (20, 64, 20) with no margin, y 63..65. */
    private static WalkBox box() {
        return WalkBox.around("world", 2.5, 65.0, 2.5, 20.5, 64, 20.5, 0, WalkCaptureTest.MIN_Y, WalkCaptureTest.MAX_Y);
    }

    private void tickUntilDone(CompletableFuture<?> future) {
        for (int i = 0; i < 100 && !future.isDone(); i++) {
            service.tick();
        }
        assertTrue(future.isDone(), "completed within 100 ticks");
    }

    @Test
    void theBoxIsTheLegPlusTheMarginUpAndDownToo() {
        WalkBox box = WalkBox.around("world", 10.7, 65.0, -3.2, 40.1, 70, 5.9, 16, -64, 320);
        assertEquals(new WalkBox("world", 10 - 16, 64 - 16, -4 - 16, 40 + 16, 70 + 1 + 16, 5 + 16), box);
        assertEquals(-1, box.minChunkX());
        assertEquals(3, box.maxChunkX());
        assertEquals(box.chunkCount(), box.chunkKeys().size());

        WalkBox low = WalkBox.around("world", 0, -63.0, 0, 0, -64, 0, 16, -64, 320);
        assertEquals(-64, low.minY(), "clamped to the world");
    }

    @Test
    void aBoxIsCapturedOverTicksWithinTheBudgetThenSearchable() {
        CompletableFuture<WalkSnapshotService.WalkCapture> future = service.capture(chunks, GateCells.NONE, box());
        assertFalse(future.isDone(), "nothing is read in the request itself");

        service.tick();
        assertEquals(4, chunks.captured.size(), "four chunks per tick");
        assertTrue(future.isDone());

        WalkSnapshotService.WalkCapture capture = future.join();
        assertTrue(capture.ready());
        assertEquals(4, capture.terrain().chunkCount());
        assertEquals(Set.of(BlockKey.pack(6, 65, 4), BlockKey.pack(10, 65, 2)), Set.copyOf(capture.doorBlocks()),
            "the door blocks inside the box (y 63..65; the door's upper half at 66 is outside)");

        WalkResult result = new WalkSearch().find(WalkRequest.toPoint(capture.terrain().terrain(),
            2.5, 65.0, 2.5, 6.5, 64, 6.5, 0.25));
        assertEquals(WalkResult.Status.FOUND, result.status(), "into the house: " + result);
    }

    @Test
    void underLagOneChunkPerTick() {
        tps[0] = 12.0;
        CompletableFuture<WalkSnapshotService.WalkCapture> future = service.capture(chunks, GateCells.NONE, box());
        service.tick();
        assertEquals(1, chunks.captured.size());
        assertFalse(future.isDone());
        tickUntilDone(future);
        assertEquals(4, chunks.captured.size());
    }

    @Test
    void anUnloadedChunkMeansNoTerrainAtOnceOrWhenItUnloadsWhileQueued() {
        chunks.unloaded.add(WalkChunk.key(1, 1));
        WalkSnapshotService.WalkCapture now = service.capture(chunks, GateCells.NONE, box()).join();
        assertEquals(WalkSnapshotService.Status.UNLOADED, now.status());
        assertTrue(chunks.captured.isEmpty(), "nothing captured, nothing loaded");

        chunks.unloaded.clear();
        tps[0] = 12.0; // one per tick, so the unload can happen in between
        CompletableFuture<WalkSnapshotService.WalkCapture> later = service.capture(chunks, GateCells.NONE, box());
        service.tick();
        chunks.unloaded.add(WalkChunk.key(1, 1));
        tickUntilDone(later);
        assertEquals(WalkSnapshotService.Status.UNLOADED, later.join().status());
    }

    @Test
    void aTooLargeBoxIsRefused() {
        WalkBox huge = new WalkBox("world", 0, 60, 0, 16 * 8 - 1, 70, 16 * 8 - 1);
        assertEquals(WalkSnapshotService.Status.TOO_LARGE, service.capture(chunks, GateCells.NONE, huge).join().status());
    }

    @Test
    void freshChunksAreSharedAcrossRequestsAndStaleOnesRecaptured() {
        tickUntilDone(service.capture(chunks, GateCells.NONE, box()));
        assertEquals(4, chunks.captured.size());

        now[0] += 5_000;
        CompletableFuture<WalkSnapshotService.WalkCapture> again = service.capture(chunks, GateCells.NONE, box());
        assertTrue(again.isDone(), "all four from the cache, at once");
        assertEquals(4, chunks.captured.size());
        assertEquals(4, service.stats().cacheHits());

        now[0] += 6_000; // 11 s > ttl 10 s
        CompletableFuture<WalkSnapshotService.WalkCapture> stale = service.capture(chunks, GateCells.NONE, box());
        assertFalse(stale.isDone());
        tickUntilDone(stale);
        assertEquals(8, chunks.captured.size());
        assertEquals(4, service.stats().cachedChunks());
        assertTrue(service.stats().cachedBytes() > 0);
        assertEquals(8, service.stats().chunksCaptured());
    }

    @Test
    void aTallerBoxRecapturesTheChunkWithTheUnionOfSections() {
        tickUntilDone(service.capture(chunks, GateCells.NONE, box()));
        WalkBox taller = new WalkBox("world", 2, 40, 2, 20, 90, 20);
        CompletableFuture<WalkSnapshotService.WalkCapture> future = service.capture(chunks, GateCells.NONE, taller);
        assertFalse(future.isDone(), "the cached band does not cover y 40..90");
        tickUntilDone(future);
        WalkSnapshotService.WalkCapture capture = future.join();
        assertTrue(capture.terrain().isSolid(5, 40, 5));
        assertTrue(capture.terrain().isPassable(5, 90, 5));

        assertTrue(service.capture(chunks, GateCells.NONE, box()).isDone(), "the union still covers the small box");
    }

    @Test
    void twoRequestsForTheSameChunksCaptureThemOnce() {
        CompletableFuture<WalkSnapshotService.WalkCapture> a = service.capture(chunks, GateCells.NONE, box());
        CompletableFuture<WalkSnapshotService.WalkCapture> b = service.capture(chunks, GateCells.NONE, box());
        tickUntilDone(a);
        tickUntilDone(b);
        assertEquals(4, chunks.captured.size());
        assertNotSame(a.join(), b.join());
    }

    @Test
    void aCancelledRequestsChunksAreNotCaptured() {
        CompletableFuture<WalkSnapshotService.WalkCapture> future = service.capture(chunks, GateCells.NONE, box());
        future.cancel(false);
        service.tick();
        assertTrue(chunks.captured.isEmpty());
        assertEquals(0, service.stats().pendingRequests());
    }

    @Test
    void changedGateFootprintsDropTheWorldsChunks() {
        GateCells one = gates(Map.of(BlockKey.pack(5, 65, 24), 7));
        tickUntilDone(service.capture(chunks, one, box()));
        assertTrue(service.capture(chunks, gates(Map.of(BlockKey.pack(5, 65, 24), 7)), box()).isDone(),
            "equal footprints keep the cache");
        CompletableFuture<WalkSnapshotService.WalkCapture> changed =
            service.capture(chunks, gates(Map.of(BlockKey.pack(5, 65, 24), 8)), box());
        assertFalse(changed.isDone());
        tickUntilDone(changed);
        assertEquals(8, chunks.captured.size());
    }

    @Test
    void stopCompletesWaitingRequestsAndTheWorldMustMatch() {
        CompletableFuture<WalkSnapshotService.WalkCapture> future = service.capture(chunks, GateCells.NONE, box());
        service.stop();
        assertEquals(WalkSnapshotService.Status.UNLOADED, future.join().status());
        assertEquals(0, service.stats().cachedChunks());
        assertThrows(IllegalArgumentException.class, () -> service.capture(chunks, GateCells.NONE,
            new WalkBox("nether", 0, 0, 0, 1, 1, 1)));
    }

    @Test
    void theBukkitPortReadsLoadedFlagsAndASnapshotWithoutExtras() {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(2, 3)).thenReturn(true);
        when(world.getChunkAt(2, 3)).thenReturn(chunk);
        when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
        when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);

        WalkSnapshotService.WorldChunks port = WalkSnapshotService.of(world);
        assertEquals("world", port.name());
        assertEquals(-64, port.minY());
        assertEquals(320, port.maxY());
        assertTrue(port.isLoaded(2, 3));
        assertFalse(port.isLoaded(4, 3));
        assertEquals("STONE", port.blocks(2, 3).materialAt(1, 64, 1));
    }

    private static GateCells gates(Map<Long, Integer> cells) {
        record MapGates(Map<Long, Integer> cells) implements GateCells {
            @Override
            public OptionalInt doorAt(int x, int y, int z) {
                Integer id = cells.get(BlockKey.pack(x, y, z));
                return id == null ? OptionalInt.empty() : OptionalInt.of(id);
            }
        }
        return new MapGates(cells);
    }
}
