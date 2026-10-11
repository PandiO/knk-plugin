package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.roads.RoadTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The resumable queue (DESIGN §9): order, duplicates, persistence, and the "built since start" skip. */
class BuildQueueStateTest {

    @TempDir
    Path dir;

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 9, 28, 12, 0, 0, 0, ZoneOffset.UTC);

    private static RoadTile tile(int x, int z, OffsetDateTime builtAt) {
        return new RoadTile(x * 100 + z, "world", x, z, 1, builtAt, 1, false, 0, 0, 0, 0, List.of());
    }

    @Test
    void keepsOrderDropsDuplicatesAndCounts() {
        BuildQueueState state = new BuildQueueState();
        UUID admin = UUID.randomUUID();
        assertEquals(3, state.enqueue(List.of(new TileKey("world", 0, 0), new TileKey("world", 1, 0), new TileKey("world", 0, 0),
            new TileKey("world", 0, 1)), admin, "radius 600", START));
        assertEquals(1, state.enqueue(List.of(new TileKey("world", 1, 0), new TileKey("world", 5, 5)), null, "other", START.plusMinutes(1)));

        assertEquals(4, state.pendingCount());
        assertEquals(4, state.totalCount());
        assertEquals("radius 600", state.label(), "a running queue keeps its first label and requester");
        assertEquals(Optional.of(admin), state.requester());
        assertEquals(Optional.of(new TileKey("world", 0, 0)), state.next());
        state.completed();
        assertEquals(1, state.doneCount());
        assertEquals(3, state.pendingCount());
    }

    @Test
    void skipsTilesBuiltAfterTheQueueStarted() {
        BuildQueueState state = new BuildQueueState();
        state.enqueue(List.of(new TileKey("world", 0, 0), new TileKey("world", 1, 0), new TileKey("world", 2, 0)), null, "all", START);

        int skipped = state.skipBuiltSince(List.of(
            tile(0, 0, START.plusMinutes(5)),   // built after the start → done before the restart
            tile(1, 0, START.minusDays(1)),     // an old build → still pending
            tile(2, 0, null)));                 // never built

        assertEquals(1, skipped);
        assertEquals(List.of(new TileKey("world", 1, 0), new TileKey("world", 2, 0)), state.pending());
        assertEquals(1, state.doneCount());
    }

    @Test
    void persistsAndReloads() throws Exception {
        Path file = dir.resolve("roads").resolve("build-queue.json");
        BuildQueueState state = new BuildQueueState();
        UUID admin = UUID.randomUUID();
        state.enqueue(List.of(new TileKey("world", -1, 3), new TileKey("world", 0, 3)), admin, "dirty tiles of world", START);
        state.next();
        state.completed();
        state.save(file);

        BuildQueueState loaded = BuildQueueState.load(file).orElseThrow();
        assertEquals(List.of(new TileKey("world", 0, 3)), loaded.pending());
        assertEquals(Optional.of(admin), loaded.requester());
        assertEquals("dirty tiles of world", loaded.label());
        assertEquals(START, loaded.startedAt());
        assertEquals(1, loaded.doneCount());
        assertEquals(2, loaded.totalCount());

        loaded.clear();
        loaded.save(file);
        assertFalse(Files.exists(file), "an empty queue leaves no file");
        assertTrue(BuildQueueState.load(file).isEmpty());
    }
}
