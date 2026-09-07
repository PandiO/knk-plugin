package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GateLoaderAdapterTest {
    private static final double EPSILON = 0.001;

    @Test
    void loadAndCacheGate_ComputesBasisVectorsAndMotion() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(1);
        dto.setName("Basis Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("LATERAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setGeometryDepth(1);
        dto.setMotionDistanceBlocks(3);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        dto.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        dto.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        List<GateBlockSnapshotDto> snapshots = new ArrayList<>();
        adapter.loadAndCacheGate(dto, snapshots);

        CachedGate gate = gateManager.getGate(1);
        assertNotNull(gate);

        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector nAxis = gate.getNAxis();
        Vector motion = gate.getMotionVector();

        assertNotNull(uAxis);
        assertNotNull(vAxis);
        assertNotNull(nAxis);
        assertNotNull(motion);

        assertEquals(1, uAxis.getX(), EPSILON);
        assertEquals(1, vAxis.getY(), EPSILON);
        assertEquals(1, nAxis.getZ(), EPSILON);
        // LATERAL slides sideways along the u-axis, not through the door plane.
        assertEquals(3, motion.getX(), EPSILON);

        // Cardinal gate: the lattice step must equal the unit axis (GCD-reduction is a no-op).
        assertEquals(1, gate.getUStep().getX(), EPSILON);
        assertEquals(1, gate.getVStep().getY(), EPSILON);
        assertEquals(1, gate.getNStep().getZ(), EPSILON);
    }

    @Test
    void loadAndCacheGate_MapsPassThroughFields() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(2);
        dto.setName("PassThrough Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setGeometryDepth(1);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        dto.setAllowPassThrough(true);
        dto.setPassThroughDurationSeconds(6);

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(2);
        assertNotNull(gate);
        assertTrue(gate.isAllowPassThrough());
        assertEquals(6, gate.getPassThroughDurationSeconds());
    }

    @Test
    void loadAndCacheGate_DefaultsPassThroughFieldsWhenAbsentFromDto() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(3);
        dto.setName("Legacy Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setGeometryDepth(1);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // allowPassThrough/passThroughDurationSeconds intentionally left unset

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(3);
        assertNotNull(gate);
        assertFalse(gate.isAllowPassThrough());
        assertEquals(2, gate.getPassThroughDurationSeconds());
    }

    @Test
    void loadForDistrict_FetchesOnlyThatDistrictsGatesAndCachesThem() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);
        GateStructuresApi api = mock(GateStructuresApi.class);

        // getByDistrict (a query-filtered search) returns the backend's lightweight list DTO -
        // only the id is reliable from it, no geometry fields.
        GateStructureDto summary = new GateStructureDto();
        summary.setId(5);
        summary.setName("District Gate");

        // getById returns the full DTO, including geometry - this is what loadForDistrict must
        // actually use to build the CachedGate, not the summary above.
        GateStructureDto fullDto = new GateStructureDto();
        fullDto.setId(5);
        fullDto.setName("District Gate");
        fullDto.setGateType("SLIDING");
        fullDto.setMotionType("VERTICAL");
        fullDto.setGeometryDefinitionMode("PLANE_GRID");
        fullDto.setAnimationDurationTicks(60);
        fullDto.setAnimationTickRate(1);
        fullDto.setGeometryDepth(1);
        fullDto.setAnchorPoint("{\"x\":100,\"y\":64,\"z\":100}");

        when(api.getByDistrict(7)).thenReturn(CompletableFuture.completedFuture(List.of(summary)));
        when(api.getById(5)).thenReturn(CompletableFuture.completedFuture(fullDto));
        when(api.getGateSnapshots(5)).thenReturn(CompletableFuture.completedFuture(List.of()));

        adapter.loadForDistrict(api, 7).join();

        CachedGate cached = gateManager.getGate(5);
        assertNotNull(cached);
        // Proves the full (getById) DTO's anchor was used, not a (0,0,0) fallback from the
        // summary lacking an anchorPoint at all - the exact regression this test guards against.
        assertEquals(new Vector(100, 64, 100), cached.getAnchorPoint());
        verify(api, never()).getAll();
        verify(api).getById(5);
    }

    @Test
    void loadForDistrict_SkipsGatesMissingAnId() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto dtoWithoutId = new GateStructureDto();
        dtoWithoutId.setName("Malformed Gate");

        when(api.getByDistrict(9)).thenReturn(CompletableFuture.completedFuture(List.of(dtoWithoutId)));

        assertDoesNotThrow(() -> adapter.loadForDistrict(api, 9).join());
        verify(api, never()).getById(anyInt());
        verify(api, never()).getGateSnapshots(anyInt());
    }

    @Test
    void loadForDistrict_SkipsGateWhenFullLookupReturnsNothing() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto summary = new GateStructureDto();
        summary.setId(6);
        summary.setName("Vanished Gate");

        when(api.getByDistrict(10)).thenReturn(CompletableFuture.completedFuture(List.of(summary)));
        when(api.getById(6)).thenReturn(CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> adapter.loadForDistrict(api, 10).join());
        assertNull(gateManager.getGate(6));
        verify(api, never()).getGateSnapshots(anyInt());
    }

    @Test
    void loadAndCacheGate_ComputesBasisVectorsForDiagonalFaceDirection() {
        // High-risk case flagged in the roadmap (docs/features/gate-structure-animation/
        // IMPLEMENTATION_ROADMAP.md, Risk Management: "Diagonal Gate Geometry Calculation"):
        // a diagonal reference point must produce a correctly signed, correctly ordered
        // cross product, not just the trivial axis-aligned case.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(2);
        dto.setName("Diagonal Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // Diagonal (northeast-style) width axis instead of a straight cardinal direction.
        dto.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":1}");
        dto.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(2);
        assertNotNull(gate);

        double diag = 1 / Math.sqrt(2);
        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector nAxis = gate.getNAxis();

        assertEquals(diag, uAxis.getX(), EPSILON);
        assertEquals(0, uAxis.getY(), EPSILON);
        assertEquals(diag, uAxis.getZ(), EPSILON);

        assertEquals(0, vAxis.getX(), EPSILON);
        assertEquals(1, vAxis.getY(), EPSILON);
        assertEquals(0, vAxis.getZ(), EPSILON);

        // n = u x v must resolve to (-diag, 0, diag), not (diag, 0, diag) or another
        // sign/order permutation - this is exactly the class of bug diagonal gates risk.
        assertEquals(-diag, nAxis.getX(), EPSILON);
        assertEquals(0, nAxis.getY(), EPSILON);
        assertEquals(diag, nAxis.getZ(), EPSILON);

        // Lattice step vectors must be the shortest INTEGER step along the same directions -
        // (1,0,1) not (diag,0,diag) - so an integer width/height index lands on the true adjacent
        // block instead of under-shooting it (see VectorMath.primitiveLatticeStep and the bug this
        // was written to catch: a diagonal GeometryWidth>1 door collapsing to fewer distinct blocks).
        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();
        Vector nStep = gate.getNStep();

        assertEquals(1, uStep.getX(), EPSILON);
        assertEquals(0, uStep.getY(), EPSILON);
        assertEquals(1, uStep.getZ(), EPSILON);

        assertEquals(0, vStep.getX(), EPSILON);
        assertEquals(1, vStep.getY(), EPSILON);
        assertEquals(0, vStep.getZ(), EPSILON);

        assertEquals(-1, nStep.getX(), EPSILON);
        assertEquals(0, nStep.getY(), EPSILON);
        assertEquals(1, nStep.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheGate_ScalesLateralMotionByLatticeStepForDiagonalGate() {
        // Reproduces the reported bug: on a diagonal gate, LATERAL motion must slide by real
        // integer blocks (distance * (1,0,1)) rather than by the unit axis (distance * (0.7071,0,0.7071)),
        // which would only travel distance/sqrt(2) blocks along each world axis.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(20);
        dto.setName("Diagonal Lateral Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("LATERAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setMotionDistanceBlocks(4);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        dto.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":1}");
        dto.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(20);
        assertNotNull(gate);

        Vector motion = gate.getMotionVector();
        assertEquals(4, motion.getX(), EPSILON);
        assertEquals(0, motion.getY(), EPSILON);
        assertEquals(4, motion.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheGate_KeepsVerticalMotionOnWorldYWhenReferencePointIsOffset() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(12);
        dto.setName("Offset Vertical Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setGeometryWidth(3);
        dto.setGeometryHeight(8);
        dto.setGeometryDepth(1);
        dto.setMotionDistanceBlocks(3);
        dto.setAnchorPoint("{\"x\":1416.699999988079,\"y\":65,\"z\":-531.4505220512867}");
        dto.setReferencePoint1("{\"x\":1414.300000011921,\"y\":65,\"z\":-531.4283039056044}");
        dto.setReferencePoint2("{\"x\":1416,\"y\":72,\"z\":-532}");

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(12);
        assertNotNull(gate);

        Vector motion = gate.getMotionVector();
        assertEquals(0, motion.getX(), EPSILON);
        assertEquals(3, motion.getY(), EPSILON);
        assertEquals(0, motion.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheGate_FallsBackToDefaultAxesWhenReferencePointsMissing() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(3);
        dto.setName("No Reference Points Gate");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // referencePoint1/2 left null - adapter must fall back to standard axes.

        adapter.loadAndCacheGate(dto, new ArrayList<>());

        CachedGate gate = gateManager.getGate(3);
        assertNotNull(gate);

        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector nAxis = gate.getNAxis();

        assertEquals(1, uAxis.getX(), EPSILON);
        assertEquals(0, uAxis.getY(), EPSILON);
        assertEquals(0, uAxis.getZ(), EPSILON);

        assertEquals(0, vAxis.getX(), EPSILON);
        assertEquals(1, vAxis.getY(), EPSILON);
        assertEquals(0, vAxis.getZ(), EPSILON);

        assertEquals(0, nAxis.getX(), EPSILON);
        assertEquals(0, nAxis.getY(), EPSILON);
        assertEquals(1, nAxis.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheGate_UsesBlockDataJsonFromRealApiContract() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);

        GateStructureDto dto = new GateStructureDto();
        dto.setId(11);
        dto.setName("Keep Gate test");
        dto.setGateType("SLIDING");
        dto.setMotionType("VERTICAL");
        dto.setGeometryDefinitionMode("PLANE_GRID");
        dto.setAnimationDurationTicks(60);
        dto.setAnimationTickRate(1);
        dto.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");

        GateBlockSnapshotDto snapshotWithBlockData = new GateBlockSnapshotDto(
            1, 11, 0, 0, 0, 0, 0, 0, "minecraft:oak_planks", "minecraft:oak_planks", "{}", 0
        );
        GateBlockSnapshotDto snapshotWithoutBlockData = new GateBlockSnapshotDto(
            2, 11, 1, 0, 0, 1, 0, 0, "minecraft:water", "", "{}", 1
        );
        GateBlockSnapshotDto airSnapshot = new GateBlockSnapshotDto(
            3, 11, 2, 0, 0, 2, 0, 0, "minecraft:air", "minecraft:air", "{}", 2
        );

        adapter.loadAndCacheGate(dto, List.of(snapshotWithBlockData, snapshotWithoutBlockData, airSnapshot));

        List<BlockSnapshot> blocks = gateManager.getGate(11).getBlocks();
        assertEquals(2, blocks.size());
        assertEquals("minecraft:oak_planks", blocks.get(0).getBlockData());
        assertEquals("minecraft:water", blocks.get(1).getBlockData());
    }

    @Test
    void loadAll_CachesEveryGateReturnedByTheApi() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager);
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto gateTen = createGate(10, "East Gate");
        GateStructureDto gateEleven = createGate(11, "West Gate");
        when(api.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(gateTen, gateEleven)));
        when(api.getGateSnapshots(10)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(api.getGateSnapshots(11)).thenReturn(CompletableFuture.completedFuture(List.of()));

        adapter.loadAll(api).join();

        assertEquals(2, gateManager.getAllGates().size());
        assertEquals("East Gate", gateManager.getGate(10).getName());
        assertEquals("West Gate", gateManager.getGate(11).getName());
    }

    private GateStructureDto createGate(int id, String name) {
        GateStructureDto dto = new GateStructureDto();
        dto.setId(id);
        dto.setName(name);
        dto.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");
        return dto;
    }
}
