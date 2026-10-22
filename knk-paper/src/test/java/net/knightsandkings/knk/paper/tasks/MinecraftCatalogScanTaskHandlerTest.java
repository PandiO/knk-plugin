package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.MinecraftCatalogApi;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.catalog.CatalogSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * KNG-200: the catalogue scan handler sends the snapshot and completes or fails its WorldTask, calling
 * onFinished once on every path. Runs on a fake {@link CatalogSource} and same-thread executors, so no
 * server is needed.
 */
class MinecraftCatalogScanTaskHandlerTest {
    private static final CatalogEntry STONE = new CatalogEntry("minecraft:stone", true, true, 64,
        "block.minecraft.stone", "Stone", "minecraft:stone");
    private static final CatalogEntry APPLE = new CatalogEntry("minecraft:apple", true, false, 64,
        "item.minecraft.apple", null, null);

    private MinecraftCatalogApi catalogApi;
    private WorldTasksApi worldTasksApi;
    private FakeSource source;
    private MinecraftCatalogScanTaskHandler handler;
    private final AtomicInteger finished = new AtomicInteger();

    @BeforeEach
    void setUp() {
        catalogApi = mock(MinecraftCatalogApi.class);
        worldTasksApi = mock(WorldTasksApi.class);
        source = new FakeSource();
        source.candidates.add(new FakeCandidate(STONE, false, false));
        source.candidates.add(new FakeCandidate(APPLE, false, false));
        handler = new MinecraftCatalogScanTaskHandler(catalogApi, worldTasksApi, source, Runnable::run, Runnable::run);

        when(worldTasksApi.fail(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(worldTasksApi.complete(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(catalogApi.putSnapshot(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static WorldTaskDto task(String inputJson) {
        return new WorldTaskDto(7, 3, "MinecraftCatalogScan", null, null, "MinecraftCatalogScan", "Pending",
            null, null, null, null, null, inputJson, null, null, null);
    }

    @Test
    void supportsOnlyTheCatalogueScan() {
        assertTrue(handler.supports("MinecraftCatalogScan"));
        assertFalse(handler.supports("GateBlockScan"));
        assertFalse(handler.supports(null));
    }

    @Test
    void aMissingScanId_failsTheTask() {
        for (String input : new String[] {null, "", "{}", "{\"scanId\":null}", "{\"scanId\":\"x\"}", "{\"scanId\":0}", "not json"}) {
            reset(worldTasksApi);
            when(worldTasksApi.fail(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
            finished.set(0);

            handler.execute(task(input), finished::incrementAndGet);

            verify(worldTasksApi).fail(eq(7), contains("scanId"));
            verify(worldTasksApi, never()).complete(anyInt(), anyString());
            assertEquals(1, finished.get(), "input " + input);
        }
        verifyNoInteractions(catalogApi);
    }

    @Test
    void success_sendsTheSnapshotAndCompletesWithTheSummary() {
        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        ArgumentCaptor<MinecraftCatalogSnapshotDto> snapshot = ArgumentCaptor.forClass(MinecraftCatalogSnapshotDto.class);
        verify(catalogApi).putSnapshot(eq(12), snapshot.capture());
        assertEquals(1, snapshot.getValue().schemaVersion());
        assertEquals("1.21.10", snapshot.getValue().minecraftVersion());
        assertEquals("Paper test", snapshot.getValue().serverVersion());
        assertEquals("86979e0606b8c36d782bf7bc0100dc2d3559aa53d714900419f0850431d5fb12", snapshot.getValue().contentHash());
        assertEquals(List.of("minecraft:apple", "minecraft:stone"),
            snapshot.getValue().entries().stream().map(e -> e.key()).toList());

        verify(worldTasksApi).complete(7, "{\"scanId\":12,\"entryCount\":2}");
        verify(worldTasksApi, never()).fail(anyInt(), anyString());
        assertEquals(1, finished.get());
    }

    @Test
    void legacyAndAirMaterials_areLeftOut() {
        source.candidates.add(new FakeCandidate(new CatalogEntry("minecraft:legacy_stone", true, true, 64, null, null, null), true, false));
        source.candidates.add(new FakeCandidate(new CatalogEntry("minecraft:air", false, true, 64, null, null, null), false, true));
        source.candidates.add(new FakeCandidate(new CatalogEntry("minecraft:cave_air", false, true, 64, null, null, null), false, true));

        assertEquals(List.of(STONE, APPLE), source.entries());
        for (CatalogSource.Candidate candidate : source.candidates) {
            FakeCandidate fake = (FakeCandidate) candidate;
            assertEquals(!fake.legacy && !fake.air, fake.read, fake.entry.key() + " read");
        }

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);
        verify(worldTasksApi).complete(7, "{\"scanId\":12,\"entryCount\":2}");
    }

    @Test
    void aRefusedSnapshot_failsWithTheStatusAndBody() {
        when(catalogApi.putSnapshot(anyInt(), any())).thenReturn(CompletableFuture.failedFuture(new CompletionException(
            new ApiException("http://api.test/api/MinecraftCatalog/scans/12/snapshot", 409, "Request failed",
                "{\"error\":\"ScanNotActive\",\n \"message\":\"Scan 12 is Failed.\"}"))));

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(worldTasksApi).fail(eq(7), message.capture());
        assertTrue(message.getValue().contains("scan 12"), message.getValue());
        assertTrue(message.getValue().contains("HTTP 409: {\"error\":\"ScanNotActive\", \"message\":\"Scan 12 is Failed.\"}"),
            message.getValue());
        verify(worldTasksApi, never()).complete(anyInt(), anyString());
        assertEquals(1, finished.get());
    }

    @Test
    void anUnreachableApi_failsWithTheException() {
        when(catalogApi.putSnapshot(anyInt(), any()))
            .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("Connection refused")));

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        verify(worldTasksApi).fail(eq(7), contains("IOException: Connection refused"));
        assertEquals(1, finished.get());
    }

    @Test
    void aReaderError_failsTheTask_withoutSending() {
        source.candidates.add(new CatalogSource.Candidate() {
            public boolean isLegacy() { return false; }
            public boolean isAir() { return false; }
            public CatalogEntry toEntry() { throw new IllegalStateException("registry not ready"); }
        });

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        verify(worldTasksApi).fail(eq(7), contains("registry not ready"));
        verifyNoInteractions(catalogApi);
        assertEquals(1, finished.get());
    }

    @Test
    void aFailingFailCall_stillFinishesOnce() {
        when(worldTasksApi.fail(anyInt(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        handler.execute(task("{}"), finished::incrementAndGet);

        assertEquals(1, finished.get());
    }

    @Test
    void aCompleteCallThatFails_stillFinishesOnce() {
        when(worldTasksApi.complete(anyInt(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        verify(worldTasksApi, never()).fail(anyInt(), anyString());
        assertEquals(1, finished.get());
    }

    @Test
    void aSchedulerThatRefuses_failsTheTask() {
        handler = new MinecraftCatalogScanTaskHandler(catalogApi, worldTasksApi, source,
            task -> { throw new IllegalStateException("plugin disabled"); }, Runnable::run);

        handler.execute(task("{\"scanId\":12}"), finished::incrementAndGet);

        verify(worldTasksApi).fail(eq(7), contains("plugin disabled"));
        assertEquals(1, finished.get());
    }

    private static final class FakeSource implements CatalogSource {
        final List<Candidate> candidates = new ArrayList<>();

        @Override
        public String minecraftVersion() {
            return "1.21.10";
        }

        @Override
        public String serverVersion() {
            return "Paper test";
        }

        @Override
        public List<Candidate> candidates() {
            return candidates;
        }
    }

    private static final class FakeCandidate implements CatalogSource.Candidate {
        final CatalogEntry entry;
        final boolean legacy;
        final boolean air;
        boolean read;

        FakeCandidate(CatalogEntry entry, boolean legacy, boolean air) {
            this.entry = entry;
            this.legacy = legacy;
            this.air = air;
        }

        @Override
        public boolean isLegacy() {
            return legacy;
        }

        @Override
        public boolean isAir() {
            return air;
        }

        @Override
        public CatalogEntry toEntry() {
            read = true;
            return entry;
        }
    }
}
