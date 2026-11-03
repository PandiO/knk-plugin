package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateDoorDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * per-door geometry/animation/block-snapshot fields off GateStructureDto onto the new
 * GateDoorDto, embedded as GateStructureDto.gateDoors. Every test below builds a structure DTO
 * wrapping a single door DTO and asserts against the resulting cached door (keyed by the door's
 * own id, not the structure's) - mirroring how GateLoaderAdapter now actually loads gates.
 */
class GateLoaderAdapterTest {
    private static final double EPSILON = 0.001;

    private static GateStructureDto structureOf(int structureId, String name, GateDoorDto... doors) {
        GateStructureDto dto = new GateStructureDto();
        dto.setId(structureId);
        dto.setName(name);
        dto.setGateDoors(List.of(doors));
        return dto;
    }

    private static GateDoorDto doorOf(int doorId, int structureId, String name) {
        GateDoorDto dto = new GateDoorDto();
        dto.setId(doorId);
        dto.setGateStructureId(structureId);
        dto.setName(name);
        return dto;
    }

    @Test
    void aDoorKeepsItsAnchorsWorldAndOneWithoutAWorldGoesToTheMainWorld() {
        // KNG-113: a door without a world used to be animated in every world
        GateManager gateManager = new GateManager();
        GateDoorDto inHub = doorOf(1, 1, "Hub Door");
        inHub.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0,\"world\":\"hub\"}");
        GateDoorDto legacy = doorOf(2, 1, "Old Door");
        legacy.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");

        new GateLoaderAdapter(gateManager, () -> "world").loadAndCacheStructure(structureOf(1, "Gate", inHub, legacy));

        assertEquals("hub", gateManager.getGate(1).getWorldName());
        assertEquals("world", gateManager.getGate(2).getWorldName());
    }

    @Test
    void aDoorWithoutAWorldIsLeftOutWhenTheMainWorldIsNotKnown() {
        GateManager gateManager = new GateManager();
        GateDoorDto legacy = doorOf(2, 1, "Old Door");
        legacy.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");

        new GateLoaderAdapter(gateManager).loadAndCacheStructure(structureOf(1, "Gate", legacy));

        assertNull(gateManager.getGate(2));
    }

    @Test
    void loadAndCacheStructure_ComputesBasisVectorsAndMotion() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(1, 1, "Basis Door");
        door.setGateType("SLIDING");
        door.setMotionType("LATERAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setMotionDistanceBlocks(3);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(1, "Basis Gate", door));

        CachedGateDoor gate = gateManager.getGate(1);
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
    void loadAndCacheStructure_MapsPassThroughFields() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(2, 2, "PassThrough Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setAllowPassThrough(true);
        door.setPassThroughDurationSeconds(6);

        adapter.loadAndCacheStructure(structureOf(2, "PassThrough Gate", door));

        CachedGateDoor gate = gateManager.getGate(2);
        assertNotNull(gate);
        assertTrue(gate.isAllowPassThrough());
        assertEquals(6, gate.getPassThroughDurationSeconds());
    }

    @Test
    void loadAndCacheStructure_DefaultsPassThroughFieldsWhenAbsentFromDto() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(3, 3, "Legacy Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // allowPassThrough/passThroughDurationSeconds intentionally left unset

        adapter.loadAndCacheStructure(structureOf(3, "Legacy Gate", door));

        CachedGateDoor gate = gateManager.getGate(3);
        assertNotNull(gate);
        assertFalse(gate.isAllowPassThrough());
        assertEquals(2, gate.getPassThroughDurationSeconds());
    }

    @Test
    void loadAndCacheStructure_MapsPassThroughStylesAndTheirStructureOverrides() {
        // PandiO/knk-workspace#167
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(4, 4, "Styled Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setAllowAnimatedPassThrough(false);
        door.setAllowSnapPassThrough(true);
        door.setAllowWalkPathPassThrough(false);
        door.setAllowTeleportPassThrough(true);
        door.setDefaultPassThroughMethod("Snap");
        GateStructureDto structure = structureOf(4, "Styled Gate", door);
        structure.setAllowTeleportPassThroughOverride(false);
        structure.setDefaultPassThroughMethodOverride("Teleport");

        adapter.loadAndCacheStructure(structure);

        CachedGateDoor gate = gateManager.getGate(4);
        assertEquals(java.util.EnumSet.of(GatePassThroughMethod.SNAP, GatePassThroughMethod.TELEPORT), gate.getAllowedPassThroughMethods());
        assertEquals(GatePassThroughMethod.SNAP, gate.getDefaultPassThroughMethod());
        // Teleport is switched off by the structure but stays offered as the structure's default style.
        assertEquals(java.util.EnumSet.of(GatePassThroughMethod.SNAP, GatePassThroughMethod.TELEPORT), gate.getEffectiveAllowedPassThroughMethods());
        assertEquals(GatePassThroughMethod.TELEPORT, gate.getEffectiveDefaultPassThroughMethod());
    }

    @Test
    void loadAndCacheStructure_ADoorWithoutStyleFieldsKeepsThePre167Styles() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(5, 5, "Old API Door");
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        adapter.loadAndCacheStructure(structureOf(5, "Old API Gate", door));

        CachedGateDoor gate = gateManager.getGate(5);
        assertEquals(java.util.EnumSet.of(GatePassThroughMethod.DEFAULT, GatePassThroughMethod.INSTANT_OPEN, GatePassThroughMethod.TELEPORT),
            gate.getEffectiveAllowedPassThroughMethods());
        assertEquals(GatePassThroughMethod.DEFAULT, gate.getEffectiveDefaultPassThroughMethod());
    }

    @Test
    void loadForDistrict_FetchesOnlyThatDistrictsGatesAndCachesThem() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");
        GateStructuresApi api = mock(GateStructuresApi.class);

        // getByDistrict (a query-filtered search) returns the backend's lightweight list DTO -
        // only the id is reliable from it, no door geometry.
        GateStructureDto summary = new GateStructureDto();
        summary.setId(5);
        summary.setName("District Gate");

        // getByIdWithSnapshots returns the full DTO, including door geometry - this is what
        // loadForDistrict must actually use to build the CachedGateDoor, not the summary above.
        GateDoorDto door = doorOf(50, 5, "District Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":100,\"y\":64,\"z\":100}");
        GateStructureDto fullDto = structureOf(5, "District Gate", door);

        when(api.getByDistrict(7)).thenReturn(CompletableFuture.completedFuture(List.of(summary)));
        when(api.getByIdWithSnapshots(5)).thenReturn(CompletableFuture.completedFuture(fullDto));

        adapter.loadForDistrict(api, 7).join();

        CachedGateDoor cached = gateManager.getGate(50);
        assertNotNull(cached);
        // Proves the full (getByIdWithSnapshots) DTO's anchor was used, not a (0,0,0) fallback
        // from the summary lacking any door at all - the exact regression this test guards against.
        assertEquals(new Vector(100, 64, 100), cached.getAnchorPoint());
        verify(api, never()).getAll();
        verify(api).getByIdWithSnapshots(5);
    }

    @Test
    void loadForDistrict_SkipsGatesMissingAnId() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto dtoWithoutId = new GateStructureDto();
        dtoWithoutId.setName("Malformed Gate");

        when(api.getByDistrict(9)).thenReturn(CompletableFuture.completedFuture(List.of(dtoWithoutId)));

        assertDoesNotThrow(() -> adapter.loadForDistrict(api, 9).join());
        verify(api, never()).getByIdWithSnapshots(anyInt());
    }

    @Test
    void loadForDistrict_SkipsGateWhenFullLookupReturnsNothing() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto summary = new GateStructureDto();
        summary.setId(6);
        summary.setName("Vanished Gate");

        when(api.getByDistrict(10)).thenReturn(CompletableFuture.completedFuture(List.of(summary)));
        when(api.getByIdWithSnapshots(6)).thenReturn(CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> adapter.loadForDistrict(api, 10).join());
        assertTrue(gateManager.getAllGates().isEmpty());
    }

    @Test
    void loadAndCacheStructure_ComputesBasisVectorsForDiagonalFaceDirection() {
        // High-risk case flagged in the roadmap (docs/features/gate-structure-animation/
        // IMPLEMENTATION_ROADMAP.md, Risk Management: "Diagonal Gate Geometry Calculation"):
        // a diagonal reference point must produce a correctly signed, correctly ordered
        // cross product, not just the trivial axis-aligned case.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(2, 2, "Diagonal Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // Diagonal (northeast-style) width axis instead of a straight cardinal direction.
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":1}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(2, "Diagonal Gate", door));

        CachedGateDoor gate = gateManager.getGate(2);
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
    void loadAndCacheStructure_ScalesLateralMotionByLatticeStepForDiagonalGate() {
        // Reproduces the reported bug: on a diagonal gate, LATERAL motion must slide by real
        // integer blocks (distance * (1,0,1)) rather than by the unit axis (distance * (0.7071,0,0.7071)),
        // which would only travel distance/sqrt(2) blocks along each world axis.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(20, 20, "Diagonal Lateral Door");
        door.setGateType("SLIDING");
        door.setMotionType("LATERAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setMotionDistanceBlocks(4);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":1}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(20, "Diagonal Lateral Gate", door));

        CachedGateDoor gate = gateManager.getGate(20);
        assertNotNull(gate);

        Vector motion = gate.getMotionVector();
        assertEquals(4, motion.getX(), EPSILON);
        assertEquals(0, motion.getY(), EPSILON);
        assertEquals(4, motion.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheStructure_KeepsVerticalMotionOnWorldYWhenReferencePointIsOffset() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(12, 12, "Offset Vertical Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryWidth(3);
        door.setGeometryHeight(8);
        door.setGeometryDepth(1);
        door.setMotionDistanceBlocks(3);
        door.setAnchorPoint("{\"x\":1416.699999988079,\"y\":65,\"z\":-531.4505220512867}");
        door.setReferencePoint1("{\"x\":1414.300000011921,\"y\":65,\"z\":-531.4283039056044}");
        door.setReferencePoint2("{\"x\":1416,\"y\":72,\"z\":-532}");

        adapter.loadAndCacheStructure(structureOf(12, "Offset Vertical Gate", door));

        CachedGateDoor gate = gateManager.getGate(12);
        assertNotNull(gate);

        Vector motion = gate.getMotionVector();
        assertEquals(0, motion.getX(), EPSILON);
        assertEquals(3, motion.getY(), EPSILON);
        assertEquals(0, motion.getZ(), EPSILON);
    }

    @Test
    void loadAndCacheStructure_FallsBackToDefaultAxesWhenReferencePointsMissing() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(3, 3, "No Reference Points Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        // referencePoint1/2 left null - adapter must fall back to standard axes.

        adapter.loadAndCacheStructure(structureOf(3, "No Reference Points Gate", door));

        CachedGateDoor gate = gateManager.getGate(3);
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
    void loadAndCacheStructure_UsesBlockDataJsonFromRealApiContract() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(11, 11, "Keep Door test");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");

        GateBlockSnapshotDto snapshotWithBlockData = new GateBlockSnapshotDto(
            1, 11, 0, 0, 0, 0, 0, 0, "minecraft:oak_planks", "minecraft:oak_planks", "{}", 0
        );
        GateBlockSnapshotDto snapshotWithoutBlockData = new GateBlockSnapshotDto(
            2, 11, 1, 0, 0, 1, 0, 0, "minecraft:water", "", "{}", 1
        );
        GateBlockSnapshotDto airSnapshot = new GateBlockSnapshotDto(
            3, 11, 2, 0, 0, 2, 0, 0, "minecraft:air", "minecraft:air", "{}", 2
        );
        door.setBlockSnapshots(List.of(snapshotWithBlockData, snapshotWithoutBlockData, airSnapshot));

        adapter.loadAndCacheStructure(structureOf(11, "Keep Gate test", door));

        List<BlockSnapshot> blocks = gateManager.getGate(11).getBlocks();
        assertEquals(2, blocks.size());
        assertEquals("minecraft:oak_planks", blocks.get(0).getBlockData());
        assertEquals("minecraft:water", blocks.get(1).getBlockData());
    }

    @Test
    void loadAndCacheStructure_HingesDrawbridgeOnTheWidthAxis() {
        // A drawbridge hinges along its bottom edge (uAxis) and swings height-offset blocks
        // out into depth to lie flat as a bridge - rotating around nAxis (the old, wrong
        // default) would instead spin blocks within their own plane and never form a bridge.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(30, 30, "Drawbridge Door");
        door.setGateType("DRAWBRIDGE");
        door.setMotionType("ROTATION");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(90);
        door.setAnimationTickRate(1);
        door.setRotationMaxAngleDegrees(90);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(30, "Drawbridge Gate", door));

        CachedGateDoor gate = gateManager.getGate(30);
        assertNotNull(gate);
        assertEquals(gate.getUAxis(), gate.getHingeAxis());
    }

    @Test
    void loadAndCacheStructure_HingesDoubleDoorsOnTheHeightAxis() {
        // Double doors hinge along a vertical edge (vAxis) and swing width-offset blocks out
        // into depth as they open.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(31, 31, "Double Doors Door");
        door.setGateType("DOUBLE_DOORS");
        door.setMotionType("ROTATION");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setRotationMaxAngleDegrees(90);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(31, "Double Doors Gate", door));

        CachedGateDoor gate = gateManager.getGate(31);
        assertNotNull(gate);
        assertEquals(gate.getVAxis(), gate.getHingeAxis());
    }

    @Test
    void loadAll_CachesEveryDoorOfEveryStructureReturnedByTheApi() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");
        GateStructuresApi api = mock(GateStructuresApi.class);

        GateStructureDto summaryTen = new GateStructureDto();
        summaryTen.setId(10);
        summaryTen.setName("East Gate");
        GateStructureDto summaryEleven = new GateStructureDto();
        summaryEleven.setId(11);
        summaryEleven.setName("West Gate");

        GateStructureDto fullTen = structureOf(10, "East Gate", createDoor(100, 10, "East Door"));
        GateStructureDto fullEleven = structureOf(11, "West Gate", createDoor(110, 11, "West Door"));

        when(api.getAll()).thenReturn(CompletableFuture.completedFuture(List.of(summaryTen, summaryEleven)));
        when(api.getByIdWithSnapshots(10)).thenReturn(CompletableFuture.completedFuture(fullTen));
        when(api.getByIdWithSnapshots(11)).thenReturn(CompletableFuture.completedFuture(fullEleven));

        adapter.loadAll(api).join();

        assertEquals(2, gateManager.getAllGates().size());
        assertEquals("East Door", gateManager.getGate(100).getName());
        assertEquals("West Door", gateManager.getGate(110).getName());
        assertNotNull(gateManager.getStructure(10));
        assertNotNull(gateManager.getStructure(11));
    }

    private GateDoorDto createDoor(int doorId, int structureId, String name) {
        GateDoorDto dto = doorOf(doorId, structureId, name);
        dto.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");
        return dto;
    }

    // === KNG-193: the keyframed plank is built at load (DRAWBRIDGE_KEYFRAME_ANIMATION.md) ===

    private static GateDoorDto cardinalDrawbridgeDto(int id, int width, int height) {
        GateDoorDto door = doorOf(id, id, "Drawbridge " + id);
        door.setGateType("DRAWBRIDGE");
        door.setMotionType("ROTATION");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(90);
        door.setAnimationTickRate(1);
        door.setRotationMaxAngleDegrees(90);
        door.setGeometryWidth(width);
        door.setGeometryHeight(height);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":100,\"y\":64,\"z\":100,\"world\":\"world\"}");
        door.setReferencePoint1("{\"x\":" + (100 + width - 1) + ",\"y\":64,\"z\":100,\"world\":\"world\"}");
        door.setReferencePoint2("{\"x\":100,\"y\":" + (64 + height - 1) + ",\"z\":100,\"world\":\"world\"}");
        List<GateBlockSnapshotDto> blocks = new ArrayList<>();
        int snapshotId = 1;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                blocks.add(new GateBlockSnapshotDto(snapshotId, id, i, j, 0, 100 + i, 64 + j, 100,
                    "minecraft:spruce_planks", "minecraft:spruce_planks", "{}", snapshotId));
                snapshotId++;
            }
        }
        door.setBlockSnapshots(blocks);
        return door;
    }

    @Test
    void loadAndCacheStructure_BuildsTheKeyframedPlankOfADrawbridge() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", true);

        adapter.loadAndCacheStructure(structureOf(60, "Drawbridge Gate", cardinalDrawbridgeDto(60, 4, 6)));

        CachedGateDoor gate = gateManager.getGate(60);
        assertNotNull(gate);
        assertTrue(gate.hasDrawbridgeKeyframes());
        assertEquals(11, gate.getDrawbridgeKeyframes().size());
        assertEquals(24, gate.getDrawbridgeKeyframes().first().cells().size());
        assertEquals(24, gate.getDrawbridgeKeyframes().last().cells().size());
        assertEquals(5, gate.getDrawbridgeKeyframes().reach(), "deck length = GeometryHeight when MotionDistanceBlocks is 0");
        // the spatial index holds keyframe 0 (the closed scan) for the closed door
        assertEquals(60, gateManager.getSpatialIndex().lookup("world", 103, 69, 100));
        assertEquals(24, gateManager.closedFootprint(60).size());
    }

    @Test
    void loadAndCacheStructure_MotionDistanceBlocksSetsADrawbridgesDeckLength() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", true);
        GateDoorDto door = cardinalDrawbridgeDto(61, 4, 6);
        door.setMotionDistanceBlocks(9);

        adapter.loadAndCacheStructure(structureOf(61, "Long Drawbridge Gate", door));

        CachedGateDoor gate = gateManager.getGate(61);
        assertTrue(gate.hasDrawbridgeKeyframes());
        assertEquals(8, gate.getDrawbridgeKeyframes().reach());
        assertEquals(36, gate.getDrawbridgeKeyframes().last().cells().size(), "a 4-wide deck 9 blocks long");
    }

    @Test
    void loadAndCacheStructure_KeyframeSwitchOff_KeepsThePerBlockPath() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", false);

        adapter.loadAndCacheStructure(structureOf(62, "Drawbridge Gate", cardinalDrawbridgeDto(62, 4, 6)));

        CachedGateDoor gate = gateManager.getGate(62);
        assertNotNull(gate);
        assertFalse(gate.hasDrawbridgeKeyframes());
        assertEquals(24, gateManager.closedFootprint(62).size(), "the per-block path still answers");
    }

    @Test
    void loadAndCacheStructure_ShearedReferencePoints_KeepThePerBlockPath() {
        // KNG-132's basis check comes first: a door it refuses never reaches the keyframes.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", true);
        GateDoorDto door = cardinalDrawbridgeDto(63, 4, 6);
        door.setReferencePoint2("{\"x\":103,\"y\":69,\"z\":100,\"world\":\"world\"}");

        adapter.loadAndCacheStructure(structureOf(63, "Sheared Drawbridge Gate", door));

        CachedGateDoor gate = gateManager.getGate(63);
        assertNotNull(gate);
        assertFalse(gate.hasDrawbridgeKeyframes());
    }

    @Test
    void loadAndCacheStructure_DoubleDoorsAndSlidingDoors_HaveNoKeyframes() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", true);
        GateDoorDto doubleDoors = cardinalDrawbridgeDto(64, 4, 6);
        doubleDoors.setGateType("DOUBLE_DOORS");
        GateDoorDto portcullis = cardinalDrawbridgeDto(65, 4, 6);
        portcullis.setGateType("SLIDING");
        portcullis.setMotionType("VERTICAL");

        adapter.loadAndCacheStructure(structureOf(64, "Mixed Gate", doubleDoors, portcullis));

        assertFalse(gateManager.getGate(64).hasDrawbridgeKeyframes());
        assertFalse(gateManager.getGate(65).hasDrawbridgeKeyframes());
    }

    @Test
    void loadAndCacheStructure_DrawbridgeWithAnOpenScan_TakesItsReachAndLastKeyframeFromTheScan() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world", true);
        GateDoorDto door = cardinalDrawbridgeDto(66, 2, 3);
        // the deck built 4 blocks out from the hinge (K = 3), scanned from an open anchor at the hinge
        door.setOpenAnchorPoint("{\"x\":100,\"y\":64,\"z\":100,\"world\":\"world\"}");
        List<GateBlockSnapshotDto> open = new ArrayList<>();
        int snapshotId = 500;
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k <= 3; k++) {
                open.add(new GateBlockSnapshotDto(snapshotId, 66, i, 0, k, 100 + i, 64, 100 + k,
                    "minecraft:spruce_log", "minecraft:spruce_log[axis=z]", "{}", snapshotId));
                snapshotId++;
            }
        }
        door.setOpenedBlockSnapshots(open);
        door.setMotionDistanceBlocks(7); // disagrees with the scan: warned, the scan wins (D2)

        adapter.loadAndCacheStructure(structureOf(66, "Scanned Drawbridge Gate", door));

        CachedGateDoor gate = gateManager.getGate(66);
        assertTrue(gate.hasDrawbridgeKeyframes());
        assertEquals(3, gate.getDrawbridgeKeyframes().reach());
        assertEquals(8, gate.getDrawbridgeKeyframes().last().cells().size());
        for (var cell : gate.getDrawbridgeKeyframes().last().cells()) {
            assertEquals("minecraft:spruce_log[axis=z]", cell.blockData(), "the open scan is placed verbatim");
        }
        assertEquals(1, gate.getDrawbridgeKeyframes().warnings().size());
    }

    // === Mechanism 2: loading + pairing the optional open-state scan (ROTATION_GAP_FILL_DESIGN.md) ===

    @Test
    void loadAndCacheStructure_NoOpenedSnapshots_LeavesOpenBlocksEmptyAndNothingPaired() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(50, 50, "No Open Scan Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");

        GateBlockSnapshotDto closed = new GateBlockSnapshotDto(
            1, 50, 0, 0, 0, 0, 0, 0, "minecraft:oak_planks", "minecraft:oak_planks", "{}", 0
        );
        door.setBlockSnapshots(List.of(closed));

        adapter.loadAndCacheStructure(structureOf(50, "No Open Scan Gate", door));

        CachedGateDoor gate = gateManager.getGate(50);
        assertNotNull(gate);
        assertTrue(gate.getOpenBlocks().isEmpty());
        assertNull(gate.getPairedOpenBlock(1));
    }

    @Test
    void loadAndCacheStructure_VerticalGateWithOpenedSnapshotsElsewhere_PairsNearestWhenNothingLinesUpAlongTheSlide() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(51, 51, "Vertical Dual-Scan Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setOpenAnchorPoint("{\"x\":100,\"y\":0,\"z\":0}");

        GateBlockSnapshotDto closed0 = new GateBlockSnapshotDto(
            1, 51, 0, 0, 0, 0, 0, 0, "minecraft:iron_bars", "minecraft:iron_bars", "{}", 0
        );
        GateBlockSnapshotDto closed1 = new GateBlockSnapshotDto(
            2, 51, 0, 1, 0, 0, 1, 0, "minecraft:iron_bars", "minecraft:iron_bars", "{}", 1
        );
        GateBlockSnapshotDto open0 = new GateBlockSnapshotDto(
            10, 51, 0, 0, 0, 100, 0, 0, "minecraft:iron_bars", "minecraft:iron_bars", "{}", 0
        );
        GateBlockSnapshotDto open1 = new GateBlockSnapshotDto(
            11, 51, 0, 1, 0, 100, 1, 0, "minecraft:iron_bars", "minecraft:iron_bars", "{}", 1
        );
        door.setBlockSnapshots(List.of(closed0, closed1));
        door.setOpenedBlockSnapshots(List.of(open0, open1));

        adapter.loadAndCacheStructure(structureOf(51, "Vertical Dual-Scan Gate", door));

        CachedGateDoor gate = gateManager.getGate(51);
        assertNotNull(gate);
        assertEquals(2, gate.getOpenBlocks().size());

        // KNG-271: the open scan sits 100 blocks away and the door has no motion distance, so no
        // block lines up along the slide; the leftovers pair by nearest distance.
        BlockSnapshot paired0 = gate.getPairedOpenBlock(1);
        BlockSnapshot paired1 = gate.getPairedOpenBlock(2);
        assertNotNull(paired0);
        assertNotNull(paired1);
        assertEquals(10, paired0.getId());
        assertEquals(11, paired1.getId());
    }

    @Test
    void loadAndCacheStructure_RisingDoorWithChains_PairsEachBlockWithTheOneTheSlidePutsItOn() {
        // KNG-271, GateDoor 17 "Main gate" (2026-10-23): 3x8, fences at y0-4 in every column,
        // chains at y5-7 in the outer columns, open scan from the same anchor with air at y0-2
        // and fences at y3-7, rising 3. Pairing by list position (after air is dropped) sent the
        // left chains into the middle column and jammed the door; along the slide every fence
        // meets the fence 3 above it and the chains stay unpaired.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(55, 55, "Main gate");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryWidth(3);
        door.setGeometryHeight(8);
        door.setGeometryDepth(1);
        door.setMotionDistanceBlocks(3);
        door.setAnchorPoint("{\"x\":1398,\"y\":79,\"z\":-548}");
        door.setReferencePoint1("{\"x\":1400,\"y\":79,\"z\":-548}");
        door.setReferencePoint2("{\"x\":1398,\"y\":86,\"z\":-548}");
        door.setOpenAnchorPoint("{\"x\":1398,\"y\":79,\"z\":-548}");

        List<GateBlockSnapshotDto> closed = new java.util.ArrayList<>();
        List<GateBlockSnapshotDto> open = new java.util.ArrayList<>();
        int sortOrder = 0;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 8; y++) {
                String closedBlock = y < 5 ? "minecraft:spruce_fence" : (x == 1 ? "minecraft:air" : "minecraft:iron_chain");
                String openBlock = y < 3 ? "minecraft:air" : "minecraft:spruce_fence";
                closed.add(new GateBlockSnapshotDto(100 + sortOrder, 55, x, y, 0, 0, 0, 0, closedBlock, closedBlock, "{}", sortOrder));
                open.add(new GateBlockSnapshotDto(200 + sortOrder, 55, x, y, 0, 0, 0, 0, openBlock, openBlock, "{}", sortOrder));
                sortOrder++;
            }
        }
        door.setBlockSnapshots(closed);
        door.setOpenedBlockSnapshots(open);

        adapter.loadAndCacheStructure(structureOf(55, "Garrison", door));

        CachedGateDoor gate = gateManager.getGate(55);
        assertNotNull(gate);
        assertEquals(21, gate.getBlocks().size());
        assertEquals(15, gate.getOpenBlocks().size());
        int paired = 0;
        for (BlockSnapshot block : gate.getBlocks()) {
            BlockSnapshot target = gate.getPairedOpenBlock(block.getId());
            Vector from = block.getRelativePosition();
            if (from.getY() >= 5) {
                assertNull(target, "a chain was paired: " + from);
                continue;
            }
            assertNotNull(target, "a fence was left unpaired: " + from);
            assertEquals(from.getX(), target.getRelativePosition().getX(), EPSILON);
            assertEquals(from.getY() + 3, target.getRelativePosition().getY(), EPSILON);
            paired++;
        }
        assertEquals(15, paired);
    }

    @Test
    void openScanAnchorMismatch_ScanFromTheAnchor_Fits() {
        Vector anchor = new Vector(1398.4, 79, -548.6);
        List<GateBlockSnapshotDto> scan = List.of(
            new GateBlockSnapshotDto(1, 17, 0, 3, 0, 1398, 82, -549, "minecraft:spruce_fence", "minecraft:spruce_fence", "{}", 3));

        assertNull(GateLoaderAdapter.openScanAnchorMismatch(anchor, scan));
    }

    @Test
    void openScanAnchorMismatch_ScanFromAnotherAnchor_SaysWhere() {
        // KNG-270: door 17's open scan was made from y86 while the anchor is y79.
        List<GateBlockSnapshotDto> scan = List.of(
            new GateBlockSnapshotDto(1, 17, 0, 0, 0, 1398, 86, -548, "minecraft:iron_chain", "minecraft:iron_chain", "{}", 0));

        String reason = GateLoaderAdapter.openScanAnchorMismatch(new Vector(1398, 79, -548), scan);

        assertNotNull(reason);
        assertTrue(reason.contains("(1398, 86, -548)"), reason);
        assertTrue(reason.contains("(1398, 79, -548)"), reason);
    }

    @Test
    void openScanAnchorMismatch_RowsWithoutWorldCoordinates_AreNotChecked() {
        List<GateBlockSnapshotDto> scan = List.of(
            new GateBlockSnapshotDto(1, 17, 0, 0, 0, 0, 0, 0, "minecraft:oak_log", "minecraft:oak_log", "{}", 0));

        assertNull(GateLoaderAdapter.openScanAnchorMismatch(new Vector(100, 64, 100), scan));
    }

    @Test
    void loadAndCacheStructure_OpenScanWithoutOpenDoorAnchor_IsReadFromTheAnchor() {
        // KNG-270: an empty open-door anchor = the opened door stays inside the door box.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(56, 56, "In-box door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setMotionDistanceBlocks(1);
        door.setAnchorPoint("{\"x\":10,\"y\":64,\"z\":10}");
        door.setReferencePoint1("{\"x\":11,\"y\":64,\"z\":10}");
        door.setReferencePoint2("{\"x\":10,\"y\":65,\"z\":10}");
        door.setBlockSnapshots(List.of(
            new GateBlockSnapshotDto(1, 56, 0, 0, 0, 10, 64, 10, "minecraft:oak_fence", "minecraft:oak_fence", "{}", 0)));
        door.setOpenedBlockSnapshots(List.of(
            new GateBlockSnapshotDto(10, 56, 0, 1, 0, 10, 65, 10, "minecraft:oak_fence", "minecraft:oak_fence", "{}", 1)));

        adapter.loadAndCacheStructure(structureOf(56, "In-box gate", door));

        CachedGateDoor gate = gateManager.getGate(56);
        assertNotNull(gate);
        assertEquals(10, gate.getOpenAnchorPoint().getX(), EPSILON);
        assertEquals(64, gate.getOpenAnchorPoint().getY(), EPSILON);
        assertEquals(10, gate.getPairedOpenBlock(1).getId());
    }

    @Test
    void loadAndCacheStructure_OpenScanFromAnotherAnchor_IsIgnored() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(57, 57, "Stale scan door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":10,\"y\":64,\"z\":10}");
        door.setBlockSnapshots(List.of(
            new GateBlockSnapshotDto(1, 57, 0, 0, 0, 10, 64, 10, "minecraft:oak_fence", "minecraft:oak_fence", "{}", 0)));
        door.setOpenedBlockSnapshots(List.of(
            new GateBlockSnapshotDto(10, 57, 0, 0, 0, 10, 71, 10, "minecraft:spruce_stairs", "minecraft:spruce_stairs", "{}", 0)));

        adapter.loadAndCacheStructure(structureOf(57, "Stale scan gate", door));

        CachedGateDoor gate = gateManager.getGate(57);
        assertNotNull(gate);
        assertTrue(gate.getOpenBlocks().isEmpty());
        assertNull(gate.getOpenAnchorPoint());
        assertNull(gate.getPairedOpenBlock(1));
    }

    @Test
    void loadAndCacheStructure_RotationGateWithOpenedSnapshots_PairsByNearestWorldDistance() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(52, 52, "Drawbridge Dual-Scan Door");
        door.setGateType("DRAWBRIDGE");
        door.setMotionType("ROTATION");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(90);
        door.setAnimationTickRate(1);
        door.setRotationMaxAngleDegrees(90);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");
        // Deliberately far from the anchor, and with the two open blocks swapped relative to
        // where a straight-line/SortOrder correspondence would put them - nearest-neighbor must
        // still find the physically-closest match, not just index-align them.
        door.setOpenAnchorPoint("{\"x\":500,\"y\":0,\"z\":500}");

        GateBlockSnapshotDto closedNear = new GateBlockSnapshotDto(
            1, 52, 0, 0, 0, 0, 0, 0, "minecraft:oak_log", "minecraft:oak_log", "{}", 0
        );
        GateBlockSnapshotDto closedFar = new GateBlockSnapshotDto(
            2, 52, 5, 0, 0, 5, 0, 0, "minecraft:oak_log", "minecraft:oak_log", "{}", 1
        );
        // openFar (SortOrder 0) sits near closedNear's anchor-relative position; openNear
        // (SortOrder 1) sits near closedFar's - the reverse of SortOrder order.
        GateBlockSnapshotDto openFar = new GateBlockSnapshotDto(
            20, 52, 5, 0, 0, 505, 0, 500, "minecraft:oak_log", "minecraft:oak_log[axis=x]", "{}", 0
        );
        GateBlockSnapshotDto openNear = new GateBlockSnapshotDto(
            21, 52, 0, 0, 0, 500, 0, 500, "minecraft:oak_log", "minecraft:oak_log[axis=x]", "{}", 1
        );
        door.setBlockSnapshots(List.of(closedNear, closedFar));
        door.setOpenedBlockSnapshots(List.of(openFar, openNear));

        adapter.loadAndCacheStructure(structureOf(52, "Drawbridge Dual-Scan Gate", door));

        CachedGateDoor gate = gateManager.getGate(52);
        assertNotNull(gate);

        // closedNear=(0,0,0) is nearest to openNear=(500,0,500); closedFar=(5,0,5) (world
        // (5,0,5)) is nearest to openFar=(505,0,1000... ) - by 3D distance, not SortOrder index.
        BlockSnapshot pairedForNear = gate.getPairedOpenBlock(1);
        BlockSnapshot pairedForFar = gate.getPairedOpenBlock(2);
        assertNotNull(pairedForNear);
        assertNotNull(pairedForFar);
        assertEquals(21, pairedForNear.getId());
        assertEquals(20, pairedForFar.getId());

        // Item 6.10 (Decision 7): only 2 correspondence pairs is a degenerate input for the
        // Kabsch fit (fewer than 3 non-collinear pairs, "nothing to fit") - the gate must fall
        // back to no fitted transform at all, not a partial/unreliable one.
        assertNull(gate.getFittedOpenTransform());
    }

    // === Item 6.10: uniform rigid-transform fit + open-only block synthesis (ROTATION_GAP_FILL_DESIGN.md, Decision 7) ===

    @Test
    void loadAndCacheStructure_RotationGateWithThreeOrMorePairings_FitsARigidTransform() {
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(53, 53, "Drawbridge Dense Dual-Scan Door");
        door.setGateType("DRAWBRIDGE");
        door.setMotionType("ROTATION");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(90);
        door.setAnimationTickRate(1);
        door.setRotationMaxAngleDegrees(90);
        door.setAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":64,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":65,\"z\":0}");
        door.setOpenAnchorPoint("{\"x\":0,\"y\":64,\"z\":0}");

        // 3 non-collinear closed points (all at world Y=64, i.e. zero Y-component of their own),
        // mapped through a KNOWN, consistent rigid transform - pure translation along Y, chosen
        // specifically so it's ORTHOGONAL to every pairwise difference among the 3 points (which
        // all vary only in X/Z). That guarantees the true diagonal correspondence is the STRICT,
        // unambiguous minimum-total-distance assignment (any swap adds a strictly positive
        // |diff_XZ|^2 term on top of the same shared |translation|^2 baseline) - avoiding a subtler
        // failure mode where GateBlockPairing's Hungarian matcher, minimizing pure total distance
        // with no knowledge of "rotation", can legitimately prefer a globally-cheaper but
        // geometrically "twisted" assignment over the one this test was actually built from,
        // especially once a rotation is involved. Plus a 4th, open-only point with no closed
        // counterpart, placed far enough away that it's unambiguously the one left unmatched.
        Vector translation = new Vector(0, 500, 0);

        Vector closedRel1 = new Vector(5, 0, 0);
        Vector closedRel2 = new Vector(0, 0, 0);
        Vector closedRel3 = new Vector(3, 0, 4);

        GateBlockSnapshotDto closed1 = snapshotDto(1, 53, closedRel1, 0);
        GateBlockSnapshotDto closed2 = snapshotDto(2, 53, closedRel2, 1);
        GateBlockSnapshotDto closed3 = snapshotDto(3, 53, closedRel3, 2);
        door.setBlockSnapshots(List.of(closed1, closed2, closed3));

        Vector open1Rel = closedRel1.clone().add(translation);
        Vector open2Rel = closedRel2.clone().add(translation);
        Vector open3Rel = closedRel3.clone().add(translation);
        // Open-only: no closed counterpart. Distance-squared to any closed point (~4,000,000+) is
        // far larger than even an off-diagonal cost among the 3 true pairs (~250,000-250,050), so
        // the Hungarian matcher never prefers it over any true correspondence - it must be left as
        // the one unmatched column.
        Vector openOnlyRel = new Vector(2000, 0, 0);

        GateBlockSnapshotDto open1 = snapshotDto(20, 53, open1Rel, 0);
        GateBlockSnapshotDto open2 = snapshotDto(21, 53, open2Rel, 1);
        GateBlockSnapshotDto open3 = snapshotDto(22, 53, open3Rel, 2);
        GateBlockSnapshotDto openOnly = snapshotDto(23, 53, openOnlyRel, 3);
        door.setOpenedBlockSnapshots(List.of(open1, open2, open3, openOnly));

        adapter.loadAndCacheStructure(structureOf(53, "Drawbridge Dense Dual-Scan Gate", door));

        CachedGateDoor gate = gateManager.getGate(53);
        assertNotNull(gate);
        assertNotNull(gate.getFittedOpenTransform(), "3 non-collinear pairs must produce a fit");

        // Every real closed block is still paired to its true nearest-neighbor open counterpart.
        assertEquals(20, gate.getPairedOpenBlock(1).getId());
        assertEquals(21, gate.getPairedOpenBlock(2).getId());
        assertEquals(22, gate.getPairedOpenBlock(3).getId());

        // The open-only block has no closed-side pairing, but DOES get a synthesized closed-frame
        // start point once a fit exists.
        Vector synthesized = gate.getOpenOnlyBlockSynthesizedRelativePosition(23);
        assertNotNull(synthesized);

        // Round-trip sanity: applying the fitted transform to the synthesized closed-frame world
        // position must land back exactly on the open-only block's own real open-scan position.
        Vector synthesizedClosedWorldPos = gate.getAnchorPoint().clone().add(synthesized);
        Vector recoveredOpenWorldPos = gate.getFittedOpenTransform().apply(synthesizedClosedWorldPos);
        Vector realOpenWorldPos = gate.getOpenAnchorPoint().clone().add(openOnlyRel);
        assertEquals(realOpenWorldPos.getX(), recoveredOpenWorldPos.getX(), EPSILON);
        assertEquals(realOpenWorldPos.getY(), recoveredOpenWorldPos.getY(), EPSILON);
        assertEquals(realOpenWorldPos.getZ(), recoveredOpenWorldPos.getZ(), EPSILON);

        // No synthesized start point for a block that IS paired - it's handled via the closed-side
        // pairing instead, not this map.
        assertNull(gate.getOpenOnlyBlockSynthesizedRelativePosition(20));
    }

    @Test
    void loadAndCacheStructure_VerticalGateWithThreeOrMorePairings_NeverFitsATransform() {
        // Decision 7 is ROTATION-specific - VERTICAL/LATERAL's plain lerp was never the source of
        // the reported non-rigid motion, so no fit should ever be computed for them, regardless of
        // how many pairings exist.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(54, 54, "Vertical Dense Dual-Scan Door");
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setOpenAnchorPoint("{\"x\":100,\"y\":0,\"z\":0}");

        door.setBlockSnapshots(List.of(
            snapshotDto(1, 54, new Vector(0, 0, 0), 0),
            snapshotDto(2, 54, new Vector(1, 0, 0), 1),
            snapshotDto(3, 54, new Vector(0, 1, 0), 2)
        ));
        door.setOpenedBlockSnapshots(List.of(
            snapshotDto(10, 54, new Vector(0, 0, 0), 0),
            snapshotDto(11, 54, new Vector(1, 0, 0), 1),
            snapshotDto(12, 54, new Vector(0, 1, 0), 2)
        ));

        adapter.loadAndCacheStructure(structureOf(54, "Vertical Dense Dual-Scan Gate", door));

        CachedGateDoor gate = gateManager.getGate(54);
        assertNotNull(gate);
        assertNull(gate.getFittedOpenTransform());
    }

    private static GateBlockSnapshotDto snapshotDto(int id, int doorId, Vector relativePos, int sortOrder) {
        return new GateBlockSnapshotDto(
            id, doorId,
            (int) Math.round(relativePos.getX()), (int) Math.round(relativePos.getY()), (int) Math.round(relativePos.getZ()),
            0, 0, 0,
            "minecraft:oak_log", "minecraft:oak_log", "{}", sortOrder
        );
    }

    @Test
    @org.junit.jupiter.api.Disabled("WorldEdit is compileOnly dependency, not available in test classpath - "
        + "this path invokes GateRegionDataFormat, whose methods reference WorldEdit types, same "
        + "constraint as WorldGuardIntegrationTest/GateDoorRegionCaptureHandlerTest")
    void loadAndCacheStructure_RegionModeDoor_ProjectsCapturedFootprintIntoUVSpace() {
        // Item 6.6 (WORLDGUARD_REGION_FEASIBILITY.md §9.3): a REGION-mode door's captured
        // ClosedRegionData is projected into u/v index space once at load time. Uses a
        // horizontal-plane basis (ref2 along Z, not the more common vertical Y) since a
        // POLYGON2D capture is fundamentally an X/Z outline - see the design note on
        // GateLoaderAdapter.precomputeFootprintPolygons for why a vertical vAxis degenerates.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(60, 60, "Region Door");
        door.setGateType("SLIDING");
        door.setMotionType("LATERAL");
        door.setGeometryDefinitionMode("REGION");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":0,\"z\":1}");
        door.setClosedRegionData(
            "{\"type\":\"POLYGON2D\",\"worldName\":\"world\",\"points\":"
                + "[{\"x\":0,\"z\":0},{\"x\":4,\"z\":0},{\"x\":4,\"z\":4},{\"x\":0,\"z\":4}],"
                + "\"minY\":0,\"maxY\":1}");
        // Deliberately left blank - proves the "no capture yet" path leaves it null rather than
        // throwing or producing an empty-but-non-null list.
        door.setOpenedRegionData("");

        adapter.loadAndCacheStructure(structureOf(60, "Region Gate", door));

        CachedGateDoor gate = gateManager.getGate(60);
        assertNotNull(gate);

        List<double[]> closedFootprint = gate.getClosedFootprintUV();
        assertNotNull(closedFootprint);
        assertEquals(4, closedFootprint.size());
        assertArrayEquals(new double[]{0, 0}, closedFootprint.get(0), EPSILON);
        assertArrayEquals(new double[]{4, 0}, closedFootprint.get(1), EPSILON);
        assertArrayEquals(new double[]{4, 4}, closedFootprint.get(2), EPSILON);
        assertArrayEquals(new double[]{0, 4}, closedFootprint.get(3), EPSILON);

        assertNull(gate.getOpenFootprintUV());
    }

    @Test
    @org.junit.jupiter.api.Disabled("WorldEdit is compileOnly dependency, not available in test classpath - "
        + "this path invokes GateRegionDataFormat, whose methods reference WorldEdit types, same "
        + "constraint as WorldGuardIntegrationTest/GateDoorRegionCaptureHandlerTest")
    void loadAndCacheStructure_ConvexPolyhedronRegionDoor_ProjectsVerticalShapeWithoutDegenerating() {
        // The case POLYGON2D can't handle: a vertically-standing door (vAxis = world Y), whose
        // captured shape needs real per-vertex Y to avoid collapsing to a line - see the design
        // note on GateLoaderAdapter.precomputeFootprintPolygons. Vertices given out of order,
        // mimicking WorldEdit's own unordered Set<BlockVector3> - proves the convex-hull
        // re-ordering step (GateFrameCalculator.convexHull2D) runs and produces a correct result,
        // not just that projection alone would (which the shuffled order would fail without it).
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(62, 62, "Vertical Convex Door");
        door.setGateType("SLIDING");
        door.setMotionType("LATERAL");
        door.setGeometryDefinitionMode("REGION");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");
        door.setClosedRegionData(
            "{\"type\":\"CONVEX_POLYHEDRON\",\"worldName\":\"world\",\"points\":"
                + "[{\"x\":4,\"y\":3,\"z\":0},{\"x\":0,\"y\":0,\"z\":0},"
                + "{\"x\":4,\"y\":0,\"z\":0},{\"x\":0,\"y\":3,\"z\":0}]}");

        adapter.loadAndCacheStructure(structureOf(62, "Vertical Convex Gate", door));

        CachedGateDoor gate = gateManager.getGate(62);
        assertNotNull(gate);

        List<double[]> closedFootprint = gate.getClosedFootprintUV();
        assertNotNull(closedFootprint);
        assertEquals(4, closedFootprint.size());
        // A real 4x3 rectangle in u/v space - v (height) carries genuine variation (0..3), which
        // a POLYGON2D capture of this same vertical shape could never have produced. Checked via
        // the actual production entry point (isWithinGeometryBounds), not the package-private
        // pointInPolygon directly (net.knightsandkings.knk.core.gates, a different package here).
        assertTrue(net.knightsandkings.knk.core.gates.GateFrameCalculator.isWithinGeometryBounds(
            gate, gate.getAnchorPoint().clone().add(new Vector(2, 1.5, 0))), "center of the standing door");
        assertFalse(net.knightsandkings.knk.core.gates.GateFrameCalculator.isWithinGeometryBounds(
            gate, gate.getAnchorPoint().clone().add(new Vector(2, 5, 0))), "above the door's real height");
    }

    @Test
    void loadAndCacheStructure_PlaneGridDoor_LeavesFootprintUVNull() {
        // Non-REGION gates must not run the footprint precompute at all - confirms it's correctly
        // gated on GeometryDefinitionMode, not run unconditionally for every door.
        GateManager gateManager = new GateManager();
        GateLoaderAdapter adapter = new GateLoaderAdapter(gateManager, () -> "world");

        GateDoorDto door = doorOf(61, 61, "Plane Grid Door");
        door.setGateType("SLIDING");
        door.setMotionType("LATERAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setAnimationDurationTicks(60);
        door.setAnimationTickRate(1);
        door.setGeometryDepth(1);
        door.setAnchorPoint("{\"x\":0,\"y\":0,\"z\":0}");
        door.setReferencePoint1("{\"x\":1,\"y\":0,\"z\":0}");
        door.setReferencePoint2("{\"x\":0,\"y\":1,\"z\":0}");

        adapter.loadAndCacheStructure(structureOf(61, "Plane Grid Gate", door));

        CachedGateDoor gate = gateManager.getGate(61);
        assertNotNull(gate);
        assertNull(gate.getClosedFootprintUV());
        assertNull(gate.getOpenFootprintUV());
    }
}
