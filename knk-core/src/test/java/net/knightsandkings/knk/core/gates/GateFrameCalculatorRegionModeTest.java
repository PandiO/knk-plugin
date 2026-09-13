package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers item 6.6's REGION-mode generalization (WORLDGUARD_REGION_FEASIBILITY.md §9.3): {@link
 * GateFrameCalculator#pointInPolygon}, the {@code REGION} branch of {@link
 * GateFrameCalculator#isWithinGeometryBounds}, and the {@code REGION} branch of {@link
 * GateFrameCalculator#rasterizeRotationFrame}. Split out from {@code GateFrameCalculatorTest}
 * since this is a distinct concern (the generalization itself, not PLANE_GRID/FLOOD_FILL
 * behavior, which that file's own suite already proves is untouched - it passes unmodified
 * against this same production code).
 */
class GateFrameCalculatorRegionModeTest {

    // === pointInPolygon ===

    @Test
    void pointInPolygon_PointInsideSquare_ReturnsTrue() {
        List<double[]> square = rectangle(0, 0, 4, 4);
        assertTrue(GateFrameCalculator.pointInPolygon(2, 2, square));
    }

    @Test
    void pointInPolygon_PointOutsideSquare_ReturnsFalse() {
        List<double[]> square = rectangle(0, 0, 4, 4);
        assertFalse(GateFrameCalculator.pointInPolygon(5, 5, square));
    }

    @Test
    void pointInPolygon_PointExactlyOnEdge_ReturnsTrue() {
        // The closed frame's own scanned blocks land exactly on the captured boundary - an
        // exclusive edge test would spuriously clip the door's own outline.
        List<double[]> square = rectangle(0, 0, 4, 4);
        assertTrue(GateFrameCalculator.pointInPolygon(0, 2, square));
        assertTrue(GateFrameCalculator.pointInPolygon(4, 0, square));
    }

    @Test
    void pointInPolygon_LShapedConcavePolygon_ExcludesTheNotchButIncludesBothArms() {
        // An L shape: a 6x6 square with the top-right 3x3 quadrant notched out. Proves this is a
        // real point-in-polygon test, not just a bounding-box check - (4,4) is within the square's
        // bounding box but not within the L itself.
        List<double[]> lShape = List.of(
            new double[]{0, 0}, new double[]{6, 0}, new double[]{6, 3},
            new double[]{3, 3}, new double[]{3, 6}, new double[]{0, 6}
        );

        assertTrue(GateFrameCalculator.pointInPolygon(1, 1, lShape), "bottom-left arm");
        assertTrue(GateFrameCalculator.pointInPolygon(4, 1, lShape), "bottom-right arm");
        assertTrue(GateFrameCalculator.pointInPolygon(1, 4, lShape), "top-left arm");
        assertFalse(GateFrameCalculator.pointInPolygon(4, 4, lShape), "the notched-out quadrant");
    }

    @Test
    void pointInPolygon_FewerThanThreePoints_ReturnsFalse() {
        assertFalse(GateFrameCalculator.pointInPolygon(0, 0, List.of(new double[]{0, 0}, new double[]{1, 1})));
        assertFalse(GateFrameCalculator.pointInPolygon(0, 0, null));
    }

    // === convexHull2D (needed for CONVEX_POLYHEDRON captures, whose WorldEdit vertices arrive
    // as an unordered Set - see GateLoaderAdapter.precomputeFootprintPolygons) ===

    @Test
    void convexHull2D_SquareGivenOutOfOrder_RecoversAllFourCornersInBoundaryOrder() {
        // Deliberately shuffled input, mimicking a Set's undefined iteration order.
        List<double[]> shuffled = List.of(
            new double[]{4, 4}, new double[]{0, 0}, new double[]{4, 0}, new double[]{0, 4}
        );

        List<double[]> hull = GateFrameCalculator.convexHull2D(shuffled);

        assertEquals(4, hull.size());
        // A valid boundary trace: every consecutive pair (wrapping) must be an actual edge of the
        // square (i.e. adjacent corners, not a diagonal) - confirmed by pointInPolygon still
        // correctly containing/excluding points against the hull's own output.
        assertTrue(GateFrameCalculator.pointInPolygon(2, 2, hull), "center of the square");
        assertFalse(GateFrameCalculator.pointInPolygon(5, 5, hull), "outside the square");
        assertTrue(GateFrameCalculator.pointInPolygon(0, 0, hull), "on a corner");
    }

    @Test
    void convexHull2D_InteriorPointExcludedFromHull() {
        List<double[]> withInteriorPoint = List.of(
            new double[]{0, 0}, new double[]{4, 0}, new double[]{4, 4}, new double[]{0, 4},
            new double[]{2, 2} // strictly inside - must not appear on the hull boundary
        );

        List<double[]> hull = GateFrameCalculator.convexHull2D(withInteriorPoint);

        assertEquals(4, hull.size(), "the interior point must not become a hull vertex");
    }

    @Test
    void convexHull2D_TriangleGivenOutOfOrder_ProducesCorrectContainment() {
        List<double[]> shuffled = List.of(
            new double[]{7, 0}, new double[]{0, 0}, new double[]{0, 7}
        );

        List<double[]> hull = GateFrameCalculator.convexHull2D(shuffled);

        assertEquals(3, hull.size());
        assertTrue(GateFrameCalculator.pointInPolygon(1, 1, hull), "inside the triangle");
        assertFalse(GateFrameCalculator.pointInPolygon(5, 5, hull), "outside the triangle (past the hypotenuse)");
    }

    @Test
    void convexHull2D_DegenerateInputs_DoNotThrow() {
        assertEquals(List.of(), GateFrameCalculator.convexHull2D(null));
        assertTrue(GateFrameCalculator.convexHull2D(List.of()).isEmpty());
        assertEquals(1, GateFrameCalculator.convexHull2D(List.of(new double[]{1, 1})).size());
        // Three collinear points: not a real polygon, but must not throw.
        assertDoesNotThrow(() -> GateFrameCalculator.convexHull2D(
            List.of(new double[]{0, 0}, new double[]{1, 1}, new double[]{2, 2})));
    }

    // === isWithinGeometryBounds, REGION mode ===

    @Test
    void isWithinGeometryBounds_RegionMode_PointInsideFootprint_ReturnsTrue() {
        CachedGateDoor gate = buildRegionGate(4, 4, rectangle(0, 0, 4, 4));
        Vector worldPos = gate.getAnchorPoint().clone().add(new Vector(2, 0, 2));
        assertTrue(GateFrameCalculator.isWithinGeometryBounds(gate, worldPos));
    }

    @Test
    void isWithinGeometryBounds_RegionMode_PointOutsideFootprint_ReturnsFalse() {
        CachedGateDoor gate = buildRegionGate(4, 4, rectangle(0, 0, 4, 4));
        Vector worldPos = gate.getAnchorPoint().clone().add(new Vector(10, 0, 10));
        assertFalse(GateFrameCalculator.isWithinGeometryBounds(gate, worldPos));
    }

    @Test
    void isWithinGeometryBounds_RegionMode_NoCapturedFootprintYet_FailsOpen() {
        // Matches the box case's own "missing basis" fail-open behavior two lines above it in
        // isWithinGeometryBounds - a REGION door that hasn't been captured yet shouldn't clip
        // every block away.
        CachedGateDoor gate = buildRegionGate(4, 4, null);
        Vector worldPos = gate.getAnchorPoint().clone().add(new Vector(999, 0, 999));
        assertTrue(GateFrameCalculator.isWithinGeometryBounds(gate, worldPos));
    }

    // === rasterizeRotationFrame, REGION mode ===

    @Test
    void rasterizeRotationFrame_RegionModeWithRectangularFootprint_MatchesPlaneGridExactly() {
        // The generalization's core safety net: a REGION-mode door whose captured footprint is
        // exactly the rectangle PLANE_GRID would have derived from the same width/height must
        // rasterize to the identical cell set - proving the swap (corner list, containment
        // predicate) didn't change PLANE_GRID's own already-correct behavior, just generalized it.
        int width = 8;
        int height = 4;
        CachedGateDoor planeGridGate = buildRotationGate("PLANE_GRID", width, height, null);
        CachedGateDoor regionGate = buildRotationGate("REGION", width, height, rectangle(0, 0, width - 1, height - 1));

        List<GateFrameCalculator.RasterizedBlock> planeGridResult = GateFrameCalculator.rasterizeRotationFrame(planeGridGate, 90.0);
        List<GateFrameCalculator.RasterizedBlock> regionResult = GateFrameCalculator.rasterizeRotationFrame(regionGate, 90.0);

        assertEquals(toPositionSet(planeGridResult), toPositionSet(regionResult));
        assertFalse(regionResult.isEmpty());
    }

    @Test
    void rasterizeRotationFrame_RegionModeWithTriangularFootprint_OpenFrameStaysWithinTheFootprint() {
        // A genuinely non-rectangular footprint: a 45-degree right triangle (u+v <= width-1).
        // Unlike PLANE_GRID (whose scan can only ever produce a rectangle, so its source blocks
        // trivially match its own footprint) or the rectangular-footprint test above, this gate's
        // *source blocks* are themselves pre-filtered to the triangle - matching how 6.5's
        // RegionScanRunnable actually captures a REGION-mode gate's blocks in production (only
        // cells the admin's drawn region contains, never the full bounding rectangle). What this
        // proves: step 4's exhaustive gap-fill, at a rotated (open) angle, doesn't spill extra
        // cells into the notched-out corner the triangle deliberately excludes.
        int width = 8;
        int height = 8;
        List<double[]> triangle = List.of(
            new double[]{0, 0}, new double[]{width - 1, 0}, new double[]{0, height - 1}
        );
        CachedGateDoor regionGate = buildTriangularRotationGate(width, height, triangle);

        List<GateFrameCalculator.RasterizedBlock> openResult = GateFrameCalculator.rasterizeRotationFrame(regionGate, 90.0);

        assertFalse(openResult.isEmpty());
        Vector uStep = regionGate.getUStep();
        Vector vStep = regionGate.getVStep();
        Vector hingeAxis = regionGate.getHingeAxis();
        Vector anchor = regionGate.getAnchorPoint();

        // Not a strict per-cell boundary check: step 3b (guaranteeing every scanned source block
        // contributes at least one placed cell) can snap a block that started exactly ON the
        // triangle's hypotenuse to an integer cell a fraction of a block past it - that's a real,
        // expected consequence of rasterizing a diagonal rotation (the same reason Mechanism 1
        // exists at all), not a footprint-containment bug. What step 4's polygon-aware gap-fill
        // must never do is add a cell *deep* inside the notched-out region - e.g. near the far
        // corner (u=width-1, v=height-1), on the opposite side of the hypotenuse from every
        // scanned source block - which a box-based (rectangle) containment check would have
        // wrongly allowed.
        boolean deepInExcludedRegionPresent = openResult.stream().anyMatch(block -> {
            Vector local = block.worldPosition().clone().subtract(anchor);
            Vector unrotated = net.knightsandkings.knk.core.util.VectorMath.rotateAroundAxis(local, hingeAxis, -90.0);
            double u = unrotated.dot(uStep) / uStep.lengthSquared();
            double v = unrotated.dot(vStep) / vStep.lengthSquared();
            return u + v > width; // comfortably past the width-1 boundary, not boundary-adjacent
        });
        assertFalse(deepInExcludedRegionPresent,
            "A gap-fill cell landed deep inside the triangle's notched-out region - the polygon containment check didn't clip it");
    }

    private CachedGateDoor buildTriangularRotationGate(int width, int height, List<double[]> closedFootprintUV) {
        CachedGateDoor gate = new CachedGateDoor(
            14, 14, "Triangular Region Gate", "DRAWBRIDGE", "ROTATION", "REGION",
            90, 1,
            new Vector(0, 0, 0), width, height, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        Vector uStep = new Vector(1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, 1);
        gate.setUStep(uStep);
        gate.setVStep(vStep);
        gate.setNStep(nStep);
        gate.setHingeAxis(uStep);
        gate.setMotionVector(new Vector(0, 0, 0));
        gate.setClosedFootprintUV(closedFootprintUV);

        // Only the cells inside the triangle (u+v <= width-1) are scanned - matching how 6.5's
        // RegionScanRunnable pre-filters a REGION-mode gate's captured blocks in production.
        int sortOrder = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height && i + j <= width - 1; j++) {
                Vector relPos = uStep.clone().multiply(i).add(vStep.clone().multiply(j));
                gate.addBlock(new BlockSnapshot(sortOrder, relPos, 1, "minecraft:oak_planks", sortOrder));
                sortOrder++;
            }
        }

        return gate;
    }

    // === fixtures ===

    private static List<double[]> rectangle(double minU, double minV, double maxU, double maxV) {
        return List.of(
            new double[]{minU, minV}, new double[]{maxU, minV},
            new double[]{maxU, maxV}, new double[]{minU, maxV}
        );
    }

    private static java.util.Set<String> toPositionSet(List<GateFrameCalculator.RasterizedBlock> blocks) {
        java.util.Set<String> positions = new java.util.HashSet<>();
        for (GateFrameCalculator.RasterizedBlock block : blocks) {
            Vector pos = block.worldPosition();
            positions.add(pos.getBlockX() + "," + pos.getBlockY() + "," + pos.getBlockZ());
        }
        return positions;
    }

    private CachedGateDoor buildRegionGate(int width, int height, List<double[]> closedFootprintUV) {
        // Deliberately not "VERTICAL": isWithinGeometryBounds special-cases VERTICAL motion into
        // a Y-only isWithinVerticalOpening check before it ever reaches the REGION branch under
        // test here. "LATERAL" reaches the u/v/n projection this test actually exercises.
        CachedGateDoor gate = new CachedGateDoor(
            1, 1, "Region Test Gate", "SLIDING", "LATERAL", "REGION",
            60, 1,
            new Vector(100, 64, 100), width, height, 1,
            500.0, 500.0, true, false, true, 90,
            "north"
        );
        gate.setUStep(new Vector(1, 0, 0));
        gate.setVStep(new Vector(0, 0, 1));
        gate.setNStep(new Vector(0, 1, 0));
        gate.setClosedFootprintUV(closedFootprintUV);
        return gate;
    }

    private CachedGateDoor buildRotationGate(String geometryDefinitionMode, int width, int height, List<double[]> closedFootprintUV) {
        CachedGateDoor gate = new CachedGateDoor(
            14, 14, "Region Rotation Gate", "DRAWBRIDGE", "ROTATION", geometryDefinitionMode,
            90, 1,
            new Vector(0, 0, 0), width, height, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        Vector uStep = new Vector(1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, 1);
        gate.setUStep(uStep);
        gate.setVStep(vStep);
        gate.setNStep(nStep);
        gate.setHingeAxis(uStep);
        gate.setMotionVector(new Vector(0, 0, 0));
        gate.setClosedFootprintUV(closedFootprintUV);

        int sortOrder = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                Vector relPos = uStep.clone().multiply(i).add(vStep.clone().multiply(j));
                gate.addBlock(new BlockSnapshot(sortOrder, relPos, 1, "minecraft:oak_planks", sortOrder));
                sortOrder++;
            }
        }

        return gate;
    }
}
