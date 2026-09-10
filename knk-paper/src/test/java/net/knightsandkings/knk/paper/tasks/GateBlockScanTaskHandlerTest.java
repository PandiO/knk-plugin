package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests covering the fallback/error paths of GateBlockScanTaskHandler that don't require
 * a live Bukkit World (see repo testing notes: full scans need Bukkit runtime).
 */
class GateBlockScanTaskHandlerTest {
    private GateStructuresApi mockGateStructuresApi;
    private WorldTasksApi mockWorldTasksApi;
    private Plugin mockPlugin;
    private GateBlockScanTaskHandler handler;

    @BeforeEach
    void setUp() {
        mockGateStructuresApi = mock(GateStructuresApi.class);
        mockWorldTasksApi = mock(WorldTasksApi.class);
        mockPlugin = mock(Plugin.class);
        handler = new GateBlockScanTaskHandler(mockGateStructuresApi, mockWorldTasksApi, mockPlugin);

        when(mockWorldTasksApi.fail(anyInt(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void testCheckScanSizeLimit_WithinConfiguredCap_ReturnsNull() {
        assertNull(GateBlockScanTaskHandler.checkScanSizeLimit(400, 500));
    }

    @Test
    void testCheckScanSizeLimit_ExceedsConfiguredCap_ReturnsMessage() {
        String result = GateBlockScanTaskHandler.checkScanSizeLimit(600, 500);
        assertNotNull(result);
        assertTrue(result.contains("600"));
        assertTrue(result.contains("500"));
    }

    @Test
    void testCheckScanSizeLimit_NullConfiguredCap_FallsBackToDefault() {
        assertNull(GateBlockScanTaskHandler.checkScanSizeLimit(500, null));
        assertNotNull(GateBlockScanTaskHandler.checkScanSizeLimit(501, null));
    }

    @Test
    void testCheckScanSizeLimit_ConfiguredCapAboveAbsoluteMax_IsClampedToAbsoluteMax() {
        assertNotNull(GateBlockScanTaskHandler.checkScanSizeLimit(20001, 999999));
        assertNull(GateBlockScanTaskHandler.checkScanSizeLimit(20000, 999999));
    }

    @Test
    void testParseMaterialSet_JsonArray_NormalizesToNamespacedLowercase() {
        Set<String> result = GateBlockScanTaskHandler.parseMaterialSet("[\"minecraft:Stone\", \"oak_planks\"]");
        assertEquals(Set.of("minecraft:stone", "minecraft:oak_planks"), result);
    }

    @Test
    void testParseMaterialSet_CommaSeparatedFallback() {
        Set<String> result = GateBlockScanTaskHandler.parseMaterialSet("stone, minecraft:dirt ,water");
        assertEquals(Set.of("minecraft:stone", "minecraft:dirt", "minecraft:water"), result);
    }

    @Test
    void testParseMaterialSet_BlankOrNull_ReturnsEmptySet() {
        assertTrue(GateBlockScanTaskHandler.parseMaterialSet(null).isEmpty());
        assertTrue(GateBlockScanTaskHandler.parseMaterialSet("").isEmpty());
        assertTrue(GateBlockScanTaskHandler.parseMaterialSet("   ").isEmpty());
    }

    @Test
    void testSupports_GateBlockScanAndGateOpenedBlockScanOnly() {
        // See ROTATION_GAP_FILL_DESIGN.md, Mechanism 2: one handler serves both task types,
        // branching only on which anchor to scan from.
        assertTrue(handler.supports("GateBlockScan"));
        assertTrue(handler.supports("GateOpenedBlockScan"));
        assertFalse(handler.supports("Location"));
        assertFalse(handler.supports(null));
    }

    @Test
    void testExecute_MissingGateStructureId_FailsTaskWithoutTouchingGateApi() {
        WorldTaskDto task = createTask(1, "{}");
        AtomicBoolean finished = new AtomicBoolean(false);

        handler.execute(task, () -> finished.set(true));

        verify(mockWorldTasksApi).fail(eq(1), contains("gateStructureId"));
        verify(mockGateStructuresApi, never()).getById(anyInt());
        assertTrue(finished.get());
    }

    @Test
    void testExecute_GateApiLookupFails_FailsTaskWithReason() {
        WorldTaskDto task = createTask(2, "{\"gateStructureId\":11}");
        when(mockGateStructuresApi.getById(11)).thenReturn(
            CompletableFuture.failedFuture(new ApiException("url", 500, "boom", "detail"))
        );
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> failMessage = new AtomicReference<>();
        when(mockWorldTasksApi.fail(eq(2), anyString())).thenAnswer(invocation -> {
            failMessage.set(invocation.getArgument(1));
            return CompletableFuture.completedFuture(null);
        });

        handler.execute(task, () -> finished.set(true));

        verify(mockWorldTasksApi, timeout(1000)).fail(eq(2), anyString());
        assertTrue(finished.get());
        assertNotNull(failMessage.get());
        assertTrue(failMessage.get().contains("11"));
    }

    @Test
    void testExecute_GateNotFound_FailsTask() {
        WorldTaskDto task = createTask(3, "{\"gateStructureId\":99}");
        when(mockGateStructuresApi.getById(99)).thenReturn(CompletableFuture.completedFuture(null));
        AtomicBoolean finished = new AtomicBoolean(false);

        handler.execute(task, () -> finished.set(true));

        verify(mockWorldTasksApi, timeout(1000)).fail(eq(3), anyString());
        assertTrue(finished.get());
    }

    @Test
    void testComputeCellPosition_DiagonalWing_ProducesFourDistinctNonDuplicateCells() {
        // Reproduces the reported bug directly: a GeometryWidth=4 diagonal (south-east) wing must
        // scan 4 distinct, correctly-spaced world cells - (0,0,0),(1,0,1),(2,0,2),(3,0,3) - not
        // collapse into fewer cells via duplicate roundings, which is what happened when the old
        // code stepped by the unit axis (0.7071,0,0.7071) instead of the lattice step (1,0,1).
        Vector anchor = new Vector(0, 0, 0);
        Vector uStep = new Vector(1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, 1);

        Set<String> worldCells = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            GateBlockScanTaskHandler.CellPosition pos =
                GateBlockScanTaskHandler.computeCellPosition(anchor, uStep, vStep, nStep, i, 0, 0);

            assertEquals(i, pos.relativeX());
            assertEquals(0, pos.relativeY());
            assertEquals(i, pos.relativeZ());
            assertEquals(i, pos.worldX());
            assertEquals(0, pos.worldY());
            assertEquals(i, pos.worldZ());

            worldCells.add(pos.worldX() + "," + pos.worldY() + "," + pos.worldZ());
        }

        assertEquals(4, worldCells.size(), "Expected 4 distinct world cells, found duplicates: " + worldCells);
    }

    @Test
    void testComputeCellPosition_CardinalWing_UnaffectedByLatticeStepChange() {
        Vector anchor = new Vector(10, 64, 10);
        Vector uStep = new Vector(1, 0, 0);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(0, 0, 1);

        GateBlockScanTaskHandler.CellPosition pos =
            GateBlockScanTaskHandler.computeCellPosition(anchor, uStep, vStep, nStep, 2, 3, 1);

        assertEquals(2, pos.relativeX());
        assertEquals(3, pos.relativeY());
        assertEquals(1, pos.relativeZ());
        assertEquals(12, pos.worldX());
        assertEquals(67, pos.worldY());
        assertEquals(11, pos.worldZ());
    }

    @Test
    void testStartScan_FloodFillGateopenAnchorRequested_FailsAsOutOfScope() {
        // FLOOD_FILL has no OpenAnchorPoint-equivalent concept in v1 - see
        // ROTATION_GAP_FILL_DESIGN.md's non-goals. Exercised directly (rather than via execute())
        // since this branch fails before any Bukkit World/scheduler access is needed.
        net.knightsandkings.knk.api.dto.GateStructureDto gate = new net.knightsandkings.knk.api.dto.GateStructureDto();
        gate.setName("Flood Fill Gate");
        gate.setGeometryDefinitionMode("FLOOD_FILL");
        AtomicBoolean finished = new AtomicBoolean(false);

        handler.startScan(5, gate, true, () -> finished.set(true));

        verify(mockWorldTasksApi).fail(eq(5), contains("FLOOD_FILL"));
        assertTrue(finished.get());
    }

    private WorldTaskDto createTask(int id, String inputJson) {
        return new WorldTaskDto(
            id, 1, "GateStructure", 1, "BlockSnapshots", "GateBlockScan", "Pending",
            null, null, null, null, null,
            inputJson, null, null, null
        );
    }
}
