package net.knightsandkings.knk.paper.npcspike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.PassableState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.StandableState;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;

/** #243 spike: K&K access vetoes the Citizens examiners; everything else is theirs. */
class SpikeExaminerTest {

    private static PathPoint at(int x, int y, int z) {
        PathPoint point = mock(PathPoint.class);
        when(point.getVector()).thenReturn(new Vector(x, y, z));
        return point;
    }

    private static BlockExaminer permissive() {
        BlockExaminer inner = mock(BlockExaminer.class);
        when(inner.canStandAt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(StandableState.STANDABLE);
        when(inner.isPassable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(PassableState.PASSABLE);
        return inner;
    }

    private static final WalkBox BOX = new WalkBox("w", -10, 0, -10, 10, 20, 10);
    private static final CellAccess GATE_AT_5 = (x, feetY, z) -> x == 5 ? CellAccess.BLOCKED : 0.0;

    @Test
    void blockedCellIsVetoedBeforeTheInnerExaminersAreAsked() {
        BlockExaminer inner = permissive();
        SpikeExaminer examiner = new SpikeExaminer(GATE_AT_5, BOX, List.of(inner));
        BlockSource source = mock(BlockSource.class);
        PathPoint gate = at(5, 1, 0);

        assertEquals(StandableState.NOT_STANDABLE, examiner.canStandAt(source, gate));
        assertEquals(PassableState.IMPASSABLE, examiner.isPassable(source, gate));
        verify(inner, never()).isPassable(source, gate);
        verify(inner, never()).canStandAt(source, gate);
    }

    @Test
    void allowedCellIsDecidedByTheInnerExaminers() {
        SpikeExaminer examiner = new SpikeExaminer(GATE_AT_5, BOX, List.of(permissive()));
        BlockSource source = mock(BlockSource.class);
        PathPoint open = at(4, 1, 0);

        assertEquals(StandableState.STANDABLE, examiner.canStandAt(source, open));
        assertEquals(PassableState.PASSABLE, examiner.isPassable(source, open));
    }

    @Test
    void cellOutsideTheCaptureBoxIsBlocked() {
        SpikeExaminer examiner = new SpikeExaminer(CellAccess.OPEN, BOX, List.of(permissive()));
        BlockSource source = mock(BlockSource.class);

        assertEquals(PassableState.IMPASSABLE, examiner.isPassable(source, at(11, 1, 0)));
    }

    @Test
    void finiteAccessCostIsAddedToTheInnerCost() {
        BlockExaminer inner = permissive();
        when(inner.getCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(2F);
        SpikeExaminer examiner = new SpikeExaminer((x, y, z) -> 3.0, BOX, List.of(inner));

        assertEquals(5F, examiner.getCost(mock(BlockSource.class), at(0, 1, 0)));
    }

    @Test
    void resampleKeepsSpacingAndEndsAtTheLastPoint() {
        List<double[]> line = List.of(new double[] {0, 64, 0}, new double[] {40, 64, 0});
        List<double[]> hops = CitizensSpike.resample(line, 16);

        assertEquals(3, hops.size());
        assertEquals(16, hops.get(0)[0], 1e-9);
        assertEquals(32, hops.get(1)[0], 1e-9);
        assertEquals(40, hops.get(2)[0], 1e-9);
    }

    @Test
    void samplesPercentiles() {
        SpikeMetrics.Samples samples = new SpikeMetrics.Samples(100);
        for (int i = 1; i <= 100; i++) {
            samples.add(i);
        }
        assertEquals(50, samples.percentile(50), 1e-9);
        assertEquals(95, samples.percentile(95), 1e-9);
        assertEquals(100, samples.max(), 1e-9);
    }
}
