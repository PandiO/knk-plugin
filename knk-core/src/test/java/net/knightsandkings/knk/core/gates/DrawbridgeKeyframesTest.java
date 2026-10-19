package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Cell;
import net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Keyframe;
import net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Plan;
import net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Result;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNG-193: the keyframed plank (knk-workspace DRAWBRIDGE_KEYFRAME_ANIMATION.md §5, §9 step 1).
 * The counts come from the reference script {@code scripts/gate-animation/drawbridge_keyframes_sim.py};
 * entity 14's open scan is the dev DB seed's 49 stored blocks (design §4.2).
 */
class DrawbridgeKeyframesTest {

    // Entity 14 "Outer Gate": anchor (1378,44,-588), Ref1 (1375,44,-585), Ref2 (1378,51,-588),
    // 4 wide x 8 tall, 45 degrees; uStep (-1,0,1), e (-1,0,0), open scan reaching K = 13.
    private static final Vector ANCHOR_14 = new Vector(1378, 44, -588);
    private static final Vector OPEN_ANCHOR_14 = new Vector(1368, 44, -591);

    private static CachedGateDoor entity14() {
        return GateFrameCalculatorTest.buildRealGate14(4, 8, ANCHOR_14);
    }

    /** Door 14's stored open scan: strips k = 0..13 at y = 44, alternating a = 0,1,2,3 and a = 0.5,1.5,2.5. */
    private static CachedGateDoor entity14WithOpenScan() {
        CachedGateDoor door = entity14();
        door.setOpenAnchorPoint(OPEN_ANCHOR_14);
        int id = 1000;
        for (int k = 0; k <= 13; k++) {
            for (int i = -7; i <= 7; i++) {
                double along = i + 0.5 * k;
                boolean inside = (k % 2 == 0) ? (along >= 0 && along <= 3) : (along >= 0.5 && along <= 2.5);
                if (!inside) {
                    continue;
                }
                // world(i, k, y) = (AX - i - k, AY + y, AZ + i) in the strip basis (design appendix A)
                Vector world = new Vector(1378 - i - k, 44, -588 + i);
                door.addOpenBlock(new BlockSnapshot(id++, world.clone().subtract(OPEN_ANCHOR_14), 1,
                    "minecraft:spruce_log[axis=" + (k == 0 ? "y" : "x") + "]", id));
            }
        }
        assertEquals(49, door.getOpenBlocks().size());
        return door;
    }

    /** A door from column heights (an arch when they differ), cardinal (uStep (1,0,0)) or 45 degrees (uStep (-1,0,1)). */
    private static CachedGateDoor fromHeights(boolean diagonal, int... heights) {
        int height = 0;
        for (int h : heights) {
            height = Math.max(height, h);
        }
        CachedGateDoor door = new CachedGateDoor(7, 7, "Arch", "DRAWBRIDGE", "ROTATION", "REGION", 90, 1,
            new Vector(100, 64, 100), heights.length, height, 1, 500.0, 500.0, true, false, true, 90, "north");
        Vector uStep = diagonal ? new Vector(-1, 0, 1) : new Vector(1, 0, 0);
        door.setUStep(uStep);
        door.setVStep(new Vector(0, 1, 0));
        door.setNStep(diagonal ? new Vector(-1, 0, -1) : new Vector(0, 0, 1));
        door.setHingeAxis(uStep);
        door.setSublatticeIndex(VectorMath.sublatticeIndex(uStep));
        int id = 1;
        for (int c = 0; c < heights.length; c++) {
            for (int r = 0; r < heights[c]; r++) {
                door.addBlock(new BlockSnapshot(id++, uStep.clone().multiply(c).add(new Vector(0, r, 0)), 1,
                    "minecraft:spruce_planks", id));
            }
        }
        return door;
    }

    private static CachedGateDoor rectangle(boolean diagonal, int width, int height) {
        int[] heights = new int[width];
        java.util.Arrays.fill(heights, height);
        return fromHeights(diagonal, heights);
    }

    private static Plan plan(CachedGateDoor door) {
        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);
        assertNull(result.fallbackReason(), "expected keyframes");
        assertNotNull(result.plan());
        return result.plan();
    }

    private static Set<Long> packed(List<Cell> cells) {
        Set<Long> packed = new HashSet<>();
        for (Cell cell : cells) {
            packed.add(cell.packed());
        }
        return packed;
    }

    private static Set<Long> closedCells(CachedGateDoor door) {
        Set<Long> cells = new HashSet<>();
        for (BlockSnapshot block : door.getBlocks()) {
            cells.add(GateSpatialIndex.packCell(door.getAnchorPoint().clone().add(block.getRelativePosition())));
        }
        return cells;
    }

    private static int[] sizes(Plan plan) {
        int[] sizes = new int[plan.size()];
        for (int n = 0; n < plan.size(); n++) {
            sizes[n] = plan.keyframe(n).cells().size();
        }
        return sizes;
    }

    // --- entity 14 ---------------------------------------------------------------------------

    @Test
    void entity14WithItsOpenScan_Gives21KeyframesFrom32To49Cells() {
        CachedGateDoor door = entity14WithOpenScan();
        Plan plan = plan(door);

        assertEquals(new Vector(-1, 0, 0), plan.e());
        assertEquals(0.5, plan.f(), 1e-9);
        assertEquals(2, plan.sigma());
        assertEquals(8, plan.height());
        assertEquals(13, plan.reach());
        assertEquals(21, plan.size());
        assertEquals(32, plan.first().cells().size());
        assertEquals(49, plan.last().cells().size());
    }

    @Test
    void entity14_Keyframe0IsTheClosedScanVerbatim() {
        CachedGateDoor door = entity14WithOpenScan();
        Keyframe closed = plan(door).first();

        assertEquals(closedCells(door), packed(closed.cells()));
        for (Cell cell : closed.cells()) {
            assertEquals("minecraft:oak_planks", cell.blockData());
        }
        assertEquals(0.0, closed.startAngleDegrees(), 1e-9);
        assertEquals(0, closed.tipK());
        assertEquals(7, closed.tipY());
    }

    @Test
    void entity14_LastKeyframeIsTheStoredOpenScanCellForCell() {
        CachedGateDoor door = entity14WithOpenScan();
        Plan plan = plan(door);
        Keyframe open = plan.last();

        Map<Long, String> stored = new HashMap<>();
        for (BlockSnapshot block : door.getOpenBlocks()) {
            stored.put(GateSpatialIndex.packCell(OPEN_ANCHOR_14.clone().add(block.getRelativePosition())), block.getBlockData());
        }
        assertEquals(49, stored.size());
        assertEquals(stored.keySet(), packed(open.cells()));
        for (Cell cell : open.cells()) {
            assertEquals(stored.get(cell.packed()), cell.blockData(), "open-scan blockdata is placed verbatim");
        }

        // strips k = 0..13, alternating a = 0,1,2,3 and a = 0.5,1.5,2.5
        Map<Integer, Set<Double>> alongByStrip = new HashMap<>();
        for (Cell cell : open.cells()) {
            int[] ik = plan.stripIndices(cell.position());
            assertEquals(0, ik[2], "the deck lies flat at the hinge's height");
            alongByStrip.computeIfAbsent(ik[1], k -> new HashSet<>()).add(ik[0] + plan.f() * ik[1]);
        }
        assertEquals(14, alongByStrip.size());
        for (int k = 0; k <= 13; k++) {
            Set<Double> expected = k % 2 == 0 ? Set.of(0.0, 1.0, 2.0, 3.0) : Set.of(0.5, 1.5, 2.5);
            assertEquals(expected, alongByStrip.get(k), "strip " + k);
        }
        assertEquals(13, open.tipK());
        assertEquals(0, open.tipY());
    }

    @Test
    void entity14_KeyframeTimingIsLinearInAngleAndEndsNearTheTop() {
        Plan plan = plan(entity14WithOpenScan());

        assertEquals(0, plan.indexAt(0));
        assertEquals(plan.size() - 1, plan.indexAt(90));
        assertTrue(plan.last().startAngleDegrees() > 85 && plan.last().startAngleDegrees() < 90,
            "the last tip cell starts at " + plan.last().startAngleDegrees());
        double previous = -1;
        for (Keyframe keyframe : plan.keyframes()) {
            assertTrue(keyframe.startAngleDegrees() > previous, "start angles increase");
            previous = keyframe.startAngleDegrees();
            assertEquals(keyframe.index(), plan.indexAt(keyframe.startAngleDegrees()));
            assertEquals(keyframe.index(), plan.indexAt(keyframe.startAngleDegrees() + 1e-4));
        }
        // frame -> angle -> keyframe, over the door's 90 frames
        CachedGateDoor door = entity14WithOpenScan();
        assertEquals(0, plan.indexAtFrame(door, 0));
        assertEquals(plan.size() - 1, plan.indexAtFrame(door, 90));
        assertEquals(plan.indexAt(45.0), plan.indexAtFrame(door, 45));
        assertEquals(0, plan.indexAtFrame(door, -5));
        assertEquals(plan.size() - 1, plan.indexAtFrame(door, 500));
    }

    @Test
    void entity14_CellsBetweenIsTheUnionOfTheKeyframes() {
        Plan plan = plan(entity14WithOpenScan());

        Set<Long> expected = new HashSet<>(plan.keyframe(3).packedPositions());
        expected.addAll(plan.keyframe(4).packedPositions());
        expected.addAll(plan.keyframe(5).packedPositions());
        assertEquals(expected, plan.cellsBetween(3, 5));
        assertEquals(expected, plan.cellsBetween(5, 3));
        assertEquals(plan.first().packedPositions(), plan.cellsBetween(0, 0));
        assertEquals(plan.last().packedPositions(), plan.cellsBetween(plan.size() - 1, plan.size() + 10));
    }

    // --- rectangles without an open scan (deck counted in blocks, L = H) -----------------------

    @Test
    void diagonal4x8WithoutOpenScan_Gives22KeyframesFrom32To53() {
        Plan plan = plan(rectangle(true, 4, 8));

        assertEquals(14, plan.reach(), "K = sigma * (L - 1) = 2 * 7");
        assertEquals(22, plan.size());
        assertEquals(32, plan.first().cells().size());
        assertEquals(53, plan.last().cells().size());
    }

    @Test
    void cardinal4x8WithoutOpenScan_Gives15KeyframesFrom32To32() {
        Plan plan = plan(rectangle(false, 4, 8));

        assertEquals(new Vector(0, 0, 1), plan.e(), "a cardinal door's strips step along its normal");
        assertEquals(0.0, plan.f(), 1e-9);
        assertEquals(1, plan.sigma());
        assertEquals(7, plan.reach());
        assertEquals(15, plan.size());
        assertEquals(32, plan.first().cells().size());
        assertEquals(32, plan.last().cells().size());
    }

    @Test
    void cardinal4x8WithDeckLengthEqualToHeight_EndFramesEqualTodaysEndFrames() {
        CachedGateDoor door = rectangle(false, 4, 8);
        Plan plan = plan(door);

        for (int frame : new int[]{0, door.getAnimationDurationTicks()}) {
            Set<Long> today = new HashSet<>();
            for (BlockSnapshot block : door.getBlocks()) {
                today.add(GateSpatialIndex.packCell(GateFrameCalculator.calculateBlockPosition(door, block, frame)));
            }
            assertEquals(today, plan.keyframeAtFrame(door, frame).packedPositions(), "frame " + frame);
        }
    }

    @Test
    void cardinal4x6_Gives11Keyframes() {
        Plan plan = plan(rectangle(false, 4, 6));

        assertEquals(11, plan.size());
        assertEquals(24, plan.first().cells().size());
        assertEquals(24, plan.last().cells().size());
    }

    @Test
    void motionDistanceBlocksSetsTheDeckLengthWithoutAnOpenScan() {
        CachedGateDoor door = rectangle(false, 4, 8);
        Result result = DrawbridgeKeyframes.build(door, 10, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertTrue(result.hasPlan());
        assertEquals(9, result.plan().reach(), "K = 1 * (10 - 1)");
        assertEquals(40, result.plan().last().cells().size(), "a 4-wide deck 10 blocks long");
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void motionDistanceBlocksThatDisagreesWithTheOpenScan_WarnsAndTheOpenScanWins() {
        Result result = DrawbridgeKeyframes.build(entity14WithOpenScan(), 5, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertTrue(result.hasPlan());
        assertEquals(13, result.plan().reach());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("MotionDistanceBlocks=5"), result.warnings().get(0));

        Result agreeing = DrawbridgeKeyframes.build(entity14WithOpenScan(), 0, DrawbridgeKeyframes.Orienter.IDENTITY);
        assertTrue(agreeing.warnings().isEmpty());
    }

    // --- arched masks (§5.11) -------------------------------------------------------------------

    @Test
    void cardinalArch7x9_Gives17KeyframesFrom57To57() {
        Plan plan = plan(fromHeights(false, 7, 8, 9, 9, 9, 8, 7));

        assertEquals(17, plan.size());
        assertEquals(57, plan.first().cells().size());
        assertEquals(57, plan.last().cells().size());
    }

    @Test
    void diagonalArch5x7_Gives19KeyframesFrom33To55() {
        Plan plan = plan(fromHeights(true, 6, 7, 7, 7, 6));

        assertEquals(19, plan.size());
        assertEquals(33, plan.first().cells().size());
        assertEquals(55, plan.last().cells().size());
    }

    @Test
    void arches_Keyframe0EqualsTheMask() {
        for (CachedGateDoor door : List.of(fromHeights(false, 7, 8, 9, 9, 9, 8, 7), fromHeights(true, 6, 7, 7, 7, 6))) {
            assertEquals(closedCells(door), packed(plan(door).first().cells()));
        }
    }

    @Test
    void arches_TheApexRowIsPresentInEveryKeyframe() {
        for (CachedGateDoor door : List.of(fromHeights(false, 7, 8, 9, 9, 9, 8, 7), fromHeights(true, 6, 7, 7, 7, 6))) {
            Plan plan = plan(door);
            for (Keyframe keyframe : plan.keyframes()) {
                boolean tipPresent = false;
                for (Cell cell : keyframe.cells()) {
                    int[] iky = plan.stripIndices(cell.position());
                    if (iky[1] == keyframe.tipK() && iky[2] == keyframe.tipY()) {
                        tipPresent = true;
                    }
                }
                assertTrue(tipPresent, "keyframe " + keyframe.index() + " draws the apex row at its tip cell");
            }
        }
    }

    @Test
    void arches_NoCellLiesOutsideTheMaskOutlineAtItsRow() {
        assertCellsStayInsideTheMask(fromHeights(false, 7, 8, 9, 9, 9, 8, 7), new int[]{7, 8, 9, 9, 9, 8, 7});
        assertCellsStayInsideTheMask(fromHeights(true, 6, 7, 7, 7, 6), new int[]{6, 7, 7, 7, 6});
    }

    private static void assertCellsStayInsideTheMask(CachedGateDoor door, int[] heights) {
        Plan plan = plan(door);
        for (Keyframe keyframe : plan.keyframes()) {
            List<int[]> profile = DrawbridgeKeyframes.profileLine(keyframe.tipK(), keyframe.tipY());
            for (Cell cell : keyframe.cells()) {
                int[] iky = plan.stripIndices(cell.position());
                int step = -1;
                for (int p = 0; p < profile.size(); p++) {
                    if (profile.get(p)[0] == iky[1] && profile.get(p)[1] == iky[2]) {
                        step = p;
                    }
                }
                assertTrue(step >= 0, "every cell lies on the plank's profile line");
                int row = profile.size() > 1
                    ? DrawbridgeKeyframes.roundHalfUp(step * (plan.height() - 1) / (double) (profile.size() - 1)) : 0;
                double along = iky[0] + plan.f() * iky[1];
                int lo = (int) Math.floor(along + 1e-9);
                int hi = (int) Math.ceil(along - 1e-9);
                assertTrue(lo >= 0 && hi < heights.length && row < heights[lo] && row < heights[hi],
                    "keyframe " + keyframe.index() + " cell at a=" + along + " row " + row + " is outside the arch");
            }
        }
    }

    @Test
    void aOneRowHoleSurvivesBothRestingFrames() {
        for (boolean diagonal : new boolean[]{false, true}) {
            int[] heights = diagonal ? new int[]{6, 7, 7, 7, 6} : new int[]{7, 8, 9, 9, 9, 8, 7};
            CachedGateDoor solid = fromHeights(diagonal, heights);
            CachedGateDoor holed = fromHeights(diagonal, heights);
            int holeColumn = 2;
            int holeRow = 3;
            holed.getBlocks().removeIf(block -> {
                Vector rel = block.getRelativePosition();
                int column = diagonal ? (int) Math.round(rel.getZ()) : (int) Math.round(rel.getX());
                return column == holeColumn && Math.round(rel.getY()) == holeRow;
            });
            assertEquals(solid.getBlocks().size() - 1, holed.getBlocks().size());

            Plan solidPlan = plan(solid);
            Plan holedPlan = plan(holed);
            assertEquals(solidPlan.size(), holedPlan.size());
            assertEquals(closedCells(holed), packed(holedPlan.first().cells()), "closed: the hole is kept");
            assertFalse(holedPlan.first().packedPositions().contains(
                GateSpatialIndex.packCell(holed.getAnchorPoint().clone().add(holed.getUStep().clone().multiply(holeColumn)).add(new Vector(0, holeRow, 0)))));

            // open: whatever samples the hole's (column, row) is missing; the deck is smaller than the solid one
            Set<Long> solidOpen = solidPlan.last().packedPositions();
            Set<Long> holedOpen = holedPlan.last().packedPositions();
            assertTrue(solidOpen.containsAll(holedOpen));
            assertTrue(holedOpen.size() < solidOpen.size(), (diagonal ? "45deg" : "cardinal") + " open deck keeps the hole");
            for (Cell cell : holedPlan.last().cells()) {
                int[] iky = holedPlan.stripIndices(cell.position());
                List<int[]> profile = DrawbridgeKeyframes.profileLine(holedPlan.last().tipK(), holedPlan.last().tipY());
                int row = DrawbridgeKeyframes.roundHalfUp(iky[1] * (holedPlan.height() - 1) / (double) (profile.size() - 1));
                double along = iky[0] + holedPlan.f() * iky[1];
                assertFalse(row == holeRow && Math.floor(along) <= holeColumn && Math.ceil(along) >= holeColumn,
                    "open deck draws nothing sampled from the hole: strip " + iky[1] + " a=" + along);
            }
        }
    }

    // --- every keyframe ------------------------------------------------------------------------

    @Test
    void everyKeyframe_TipMovesOneCellHingeNeverMovesAndACellHasOneBlockdata() {
        List<CachedGateDoor> doors = List.of(entity14WithOpenScan(), entity14(), rectangle(true, 4, 8), rectangle(false, 4, 8),
            rectangle(false, 3, 4), rectangle(true, 7, 10), fromHeights(false, 7, 8, 9, 9, 9, 8, 7), fromHeights(true, 6, 7, 7, 7, 6));
        for (CachedGateDoor door : doors) {
            Plan plan = plan(door);
            long hinge = GateSpatialIndex.packCell(door.getAnchorPoint());
            String hingeData = door.getBlocks().get(0).getBlockData();
            Keyframe previous = null;
            for (Keyframe keyframe : plan.keyframes()) {
                if (previous != null) {
                    int moved = Math.max(Math.abs(keyframe.tipK() - previous.tipK()), Math.abs(keyframe.tipY() - previous.tipY()));
                    assertEquals(1, moved, door.getName() + ": tip moves one cell into keyframe " + keyframe.index());
                }
                Set<Long> seen = new HashSet<>();
                boolean hingePresent = false;
                for (Cell cell : keyframe.cells()) {
                    assertTrue(seen.add(cell.packed()), "a cell has one blockdata");
                    if (cell.packed() == hinge) {
                        hingePresent = true;
                        if (keyframe.index() < plan.size() - 1 || door.getOpenBlocks().isEmpty()) {
                            assertEquals(hingeData, cell.blockData(), "the hinge keeps its scanned blockdata");
                        }
                    }
                }
                assertTrue(hingePresent, door.getName() + ": the hinge cell is in keyframe " + keyframe.index());
                previous = keyframe;
            }
            assertEquals(0, plan.first().tipK());
            assertEquals(plan.height() - 1, plan.first().tipY());
            assertEquals(plan.reach(), plan.last().tipK());
            assertEquals(0, plan.last().tipY());
        }
    }

    @Test
    void cellsAreOrientedAtTheKeyframeAngleExceptTheHingeRow() {
        CachedGateDoor door = rectangle(false, 2, 3);
        Result result = DrawbridgeKeyframes.build(door, 0,
            (blockData, angle) -> blockData + "@" + Math.round(angle));
        Plan plan = result.plan();
        assertNotNull(plan);

        for (Cell cell : plan.first().cells()) {
            assertEquals("minecraft:spruce_planks", cell.blockData(), "keyframe 0 is the scan verbatim");
        }
        Keyframe mid = plan.keyframe(plan.size() / 2);
        long angle = Math.round(mid.startAngleDegrees());
        for (Cell cell : mid.cells()) {
            int[] iky = plan.stripIndices(cell.position());
            if (iky[1] == 0 && iky[2] == 0) {
                assertEquals("minecraft:spruce_planks", cell.blockData(), "the hinge row keeps its scanned blockdata");
            } else {
                assertEquals("minecraft:spruce_planks@" + angle, cell.blockData());
            }
        }
    }

    @Test
    void aSmallerOpeningAngleStopsTheTipPathThere() {
        CachedGateDoor door = new CachedGateDoor(8, 8, "Hatch", "TRAP", "ROTATION", "PLANE_GRID", 60, 1,
            new Vector(0, 0, 0), 4, 8, 1, 500.0, 500.0, true, false, true, 45, "north");
        door.setUStep(new Vector(1, 0, 0));
        door.setVStep(new Vector(0, 1, 0));
        door.setNStep(new Vector(0, 0, 1));
        door.setHingeAxis(door.getUStep());
        int id = 1;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 8; r++) {
                door.addBlock(new BlockSnapshot(id++, new Vector(c, r, 0), 1, "minecraft:spruce_planks", id));
            }
        }
        Plan plan = plan(door);

        assertEquals(DrawbridgeKeyframes.roundHalfUp(7 * Math.sin(Math.toRadians(45))), plan.last().tipK());
        assertEquals(DrawbridgeKeyframes.roundHalfUp(7 * Math.cos(Math.toRadians(45))), plan.last().tipY());
        assertTrue(plan.last().startAngleDegrees() <= 45);
        assertEquals(plan.size() - 1, plan.indexAtFrame(door, 60));
    }

    // --- fall-backs (today's path, one warning at load) ---------------------------------------

    @Test
    void aSlopeWithoutAUnitAxisStripDirectionIsRefused() {
        CachedGateDoor door = rectangle(false, 4, 8);
        door.setUStep(new Vector(3, 0, 2));
        door.setHingeAxis(new Vector(3, 0, 2));
        door.getBlocks().clear();
        for (int r = 0; r < 8; r++) {
            door.addBlock(new BlockSnapshot(r, new Vector(0, r, 0), 1, "minecraft:spruce_planks", r));
        }

        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertFalse(result.hasPlan());
        assertTrue(result.fallbackReason().contains("3:2"), result.fallbackReason());
    }

    @Test
    void aTwoToOneSlopeHasAUnitAxisStripDirection() {
        CachedGateDoor door = rectangle(false, 1, 4);
        door.setUStep(new Vector(2, 0, 1));
        door.setHingeAxis(new Vector(2, 0, 1));

        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertTrue(result.hasPlan(), result.fallbackReason());
        assertEquals(0, Math.round(result.plan().e().getZ()), "e is (+-1,0,0) for a 2:1 hinge");
        assertEquals(5, result.plan().sigma());
    }

    @Test
    void aClosedBlockOffTheHingePlaneIsRefused() {
        CachedGateDoor door = rectangle(false, 4, 8);
        door.addBlock(new BlockSnapshot(999, new Vector(1, 1, 1), 1, "minecraft:spruce_planks", 999));

        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertFalse(result.hasPlan());
        assertTrue(result.fallbackReason().contains("off the hinge plane"), result.fallbackReason());
    }

    @Test
    void doubleDoorsAreRefused() {
        CachedGateDoor door = new CachedGateDoor(9, 9, "Doors", "DOUBLE_DOORS", "ROTATION", "PLANE_GRID", 60, 1,
            new Vector(0, 0, 0), 4, 8, 1, 500.0, 500.0, true, false, true, 90, "north");
        door.setUStep(new Vector(1, 0, 0));
        door.setVStep(new Vector(0, 1, 0));
        door.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:spruce_planks", 1));

        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertFalse(result.hasPlan());
        assertTrue(result.fallbackReason().contains("DOUBLE_DOORS"), result.fallbackReason());
    }

    @Test
    void anAngleOutsideZeroToNinetyIsRefused() {
        for (int angle : new int[]{0, -90, 91, 180}) {
            CachedGateDoor door = new CachedGateDoor(10, 10, "Angle " + angle, "DRAWBRIDGE", "ROTATION", "PLANE_GRID", 60, 1,
                new Vector(0, 0, 0), 4, 8, 1, 500.0, 500.0, true, false, true, angle, "north");
            door.setUStep(new Vector(1, 0, 0));
            door.setVStep(new Vector(0, 1, 0));
            door.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:spruce_planks", 1));

            Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

            assertFalse(result.hasPlan(), "angle " + angle);
            assertTrue(result.fallbackReason().contains("(0, 90]"), result.fallbackReason());
        }
    }

    @Test
    void aMissingBasisIsRefused() {
        CachedGateDoor door = new CachedGateDoor(11, 11, "No basis", "DRAWBRIDGE", "ROTATION", "PLANE_GRID", 60, 1,
            new Vector(0, 0, 0), 4, 8, 1, 500.0, 500.0, true, false, true, 90, "north");
        door.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:spruce_planks", 1));

        Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);

        assertFalse(result.hasPlan());
        assertTrue(result.fallbackReason().contains("missing basis"), result.fallbackReason());
    }

    @Test
    void aNonRotationDoorIsNotKeyframed() {
        CachedGateDoor door = new CachedGateDoor(12, 12, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID", 60, 1,
            new Vector(0, 0, 0), 4, 8, 1, 500.0, 500.0, true, false, true, 90, "north");
        door.setUStep(new Vector(1, 0, 0));
        door.setVStep(new Vector(0, 1, 0));

        assertFalse(DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY).hasPlan());
    }

    @Test
    void chooseE_PointsToTheSideTheDoorOpensToward() {
        // entity 14 swings toward north-west: both (-1,0,0) and (0,0,-1) are unimodular with (-1,0,1); the x axis is taken first
        assertEquals(new Vector(-1, 0, 0), DrawbridgeKeyframes.chooseE(new Vector(-1, 0, 1), -1, 1, 90));
        // a cardinal hinge along x swings toward -z or +z depending on the hinge direction
        assertEquals(new Vector(0, 0, 1), DrawbridgeKeyframes.chooseE(new Vector(1, 0, 0), 1, 0, 90));
        assertEquals(new Vector(0, 0, -1), DrawbridgeKeyframes.chooseE(new Vector(-1, 0, 0), -1, 0, 90));
        assertNull(DrawbridgeKeyframes.chooseE(new Vector(3, 0, 2), 3, 2, 90));
    }

    @Test
    void profileLineIsNaiveAndOrderedFromTheHinge() {
        List<int[]> line = DrawbridgeKeyframes.profileLine(5, 2);
        assertEquals(6, line.size());
        assertEquals(0, line.get(0)[0]);
        assertEquals(0, line.get(0)[1]);
        assertEquals(5, line.get(5)[0]);
        assertEquals(2, line.get(5)[1]);
        for (int s = 1; s < line.size(); s++) {
            assertEquals(1, line.get(s)[0] - line.get(s - 1)[0], "one cell per major-axis step");
        }
        assertEquals(1, DrawbridgeKeyframes.profileLine(0, 0).size());
    }
}
