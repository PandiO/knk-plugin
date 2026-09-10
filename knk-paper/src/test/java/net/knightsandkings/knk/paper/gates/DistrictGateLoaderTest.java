package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateStructuresApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DistrictGateLoader - the "already loaded this session" guard that lets a
 * district's gates load automatically the first time a player enters it (see the region-
 * transition callback wired in KnKPlugin), without re-fetching on every subsequent entry. Also
 * covers Mechanism B from GATE_WORLD_SYNC_DESIGN.md: a successful load must hand the loaded gate
 * ids to GateStateSyncTask.checkAndFixGates.
 */
class DistrictGateLoaderTest {
    private GateLoaderAdapter gateLoaderAdapter;
    private GateStructuresApi gateStructuresApi;
    private GateStateSyncTask gateStateSyncTask;
    private DistrictGateLoader districtGateLoader;

    @BeforeEach
    void setUp() {
        gateLoaderAdapter = mock(GateLoaderAdapter.class);
        gateStructuresApi = mock(GateStructuresApi.class);
        gateStateSyncTask = mock(GateStateSyncTask.class);
        districtGateLoader = new DistrictGateLoader(gateLoaderAdapter, gateStructuresApi, gateStateSyncTask);
    }

    @Test
    void loadIfNotAlreadyLoaded_FetchesOnFirstEntry() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of(101, 102)));

        districtGateLoader.loadIfNotAlreadyLoaded(7);

        verify(gateLoaderAdapter, times(1)).loadForDistrict(gateStructuresApi, 7);
        assertTrue(districtGateLoader.isLoaded(7));
    }

    @Test
    void loadIfNotAlreadyLoaded_RunsWorldSyncCheckAndFixOnTheLoadedGates() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of(101, 102)));

        districtGateLoader.loadIfNotAlreadyLoaded(7);

        verify(gateStateSyncTask, times(1)).checkAndFixGates(List.of(101, 102));
    }

    @Test
    void loadIfNotAlreadyLoaded_SkipsFetchOnRepeatedEntry() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        districtGateLoader.loadIfNotAlreadyLoaded(7);
        districtGateLoader.loadIfNotAlreadyLoaded(7);
        districtGateLoader.loadIfNotAlreadyLoaded(7);

        verify(gateLoaderAdapter, times(1)).loadForDistrict(gateStructuresApi, 7);
    }

    @Test
    void loadIfNotAlreadyLoaded_LoadsEachDistrictIndependently() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(8)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        districtGateLoader.loadIfNotAlreadyLoaded(7);
        districtGateLoader.loadIfNotAlreadyLoaded(8);

        verify(gateLoaderAdapter, times(1)).loadForDistrict(gateStructuresApi, 7);
        verify(gateLoaderAdapter, times(1)).loadForDistrict(gateStructuresApi, 8);
        assertTrue(districtGateLoader.isLoaded(7));
        assertTrue(districtGateLoader.isLoaded(8));
    }

    @Test
    void loadIfNotAlreadyLoaded_AllowsRetryAfterAFailure() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network error")));

        districtGateLoader.loadIfNotAlreadyLoaded(7);

        assertFalse(districtGateLoader.isLoaded(7));

        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        districtGateLoader.loadIfNotAlreadyLoaded(7);

        verify(gateLoaderAdapter, times(2)).loadForDistrict(gateStructuresApi, 7);
        assertTrue(districtGateLoader.isLoaded(7));
    }

    @Test
    void isLoaded_FalseBeforeAnyLoad() {
        assertFalse(districtGateLoader.isLoaded(42));
        verify(gateLoaderAdapter, never()).loadForDistrict(gateStructuresApi, 42);
    }

    @Test
    void forceReload_AlwaysCallsLoaderEvenIfAlreadyLoaded() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        districtGateLoader.loadIfNotAlreadyLoaded(7);
        districtGateLoader.forceReload(7).join();

        verify(gateLoaderAdapter, times(2)).loadForDistrict(gateStructuresApi, 7);
        assertTrue(districtGateLoader.isLoaded(7));
    }

    @Test
    void forceReload_RunsWorldSyncCheckAndFixOnTheReloadedGates() {
        when(gateLoaderAdapter.loadForDistrict(eq(gateStructuresApi), eq(7)))
            .thenReturn(CompletableFuture.completedFuture(List.of(101)));

        districtGateLoader.forceReload(7).join();

        verify(gateStateSyncTask, times(1)).checkAndFixGates(List.of(101));
    }
}
