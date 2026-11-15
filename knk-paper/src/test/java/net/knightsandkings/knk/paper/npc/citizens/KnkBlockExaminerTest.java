package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.PassableState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.StandableState;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/** K&K's access vetoes the Citizens examiners (NAV-01); everything else is theirs. */
class KnkBlockExaminerTest {

    private static final CellAccess GATE_AT_5 = (x, feetY, z) -> x == 5 ? CellAccess.BLOCKED : 0.0;

    private static LocalLeg leg(CellAccess access) {
        return new LocalLeg(access, -10, 0, -10, 10, 20, 10, true);
    }

    private static PathPoint at(int x, int y, int z) {
        PathPoint point = mock(PathPoint.class);
        when(point.getVector()).thenReturn(new Vector(x, y, z));
        return point;
    }

    private static BlockExaminer permissive() {
        BlockExaminer inner = mock(BlockExaminer.class);
        when(inner.canStandAt(any(), any())).thenReturn(StandableState.STANDABLE);
        when(inner.isPassable(any(), any())).thenReturn(PassableState.PASSABLE);
        return inner;
    }

    @Test
    void aKnkVetoBeatsALaterPassable() {
        BlockExaminer inner = permissive();
        KnkBlockExaminer examiner = new KnkBlockExaminer(leg(GATE_AT_5), List.of(inner));
        BlockSource source = mock(BlockSource.class);
        PathPoint gate = at(5, 1, 0);

        assertEquals(StandableState.NOT_STANDABLE, examiner.canStandAt(source, gate));
        assertEquals(PassableState.IMPASSABLE, examiner.isPassable(source, gate));
        verify(inner, never()).isPassable(source, gate);
        verify(inner, never()).canStandAt(source, gate);
    }

    @Test
    void anAllowedCellIsDecidedByTheInnerExaminersInOrder() {
        BlockExaminer undecided = mock(BlockExaminer.class);
        when(undecided.canStandAt(any(), any())).thenReturn(StandableState.IGNORE);
        when(undecided.isPassable(any(), any())).thenReturn(PassableState.IMPASSABLE);
        KnkBlockExaminer examiner = new KnkBlockExaminer(leg(GATE_AT_5), List.of(undecided, permissive()));
        BlockSource source = mock(BlockSource.class);
        PathPoint open = at(4, 1, 0);

        assertEquals(StandableState.STANDABLE, examiner.canStandAt(source, open));
        assertEquals(PassableState.PASSABLE, examiner.isPassable(source, open), "first PASSABLE wins among Citizens' own");
        assertEquals(PassableState.IMPASSABLE,
            new KnkBlockExaminer(leg(CellAccess.OPEN), List.of(undecided)).isPassable(source, open));
    }

    @Test
    void aCellOutsideTheCapturedBoxIsBlocked() {
        KnkBlockExaminer examiner = new KnkBlockExaminer(leg(CellAccess.OPEN), List.of(permissive()));
        BlockSource source = mock(BlockSource.class);

        assertEquals(PassableState.IMPASSABLE, examiner.isPassable(source, at(11, 1, 0)));
        assertEquals(StandableState.NOT_STANDABLE, examiner.canStandAt(source, at(0, 21, 0)));
    }

    @Test
    void aFiniteAccessCostIsAddedToTheInnerCost() {
        BlockExaminer inner = permissive();
        when(inner.getCost(any(), any())).thenReturn(2F);
        KnkBlockExaminer examiner = new KnkBlockExaminer(leg((x, y, z) -> 3.0), List.of(inner));

        assertEquals(5F, examiner.getCost(mock(BlockSource.class), at(0, 1, 0)));
    }

    @Test
    void callsAndBlocksAreCounted() {
        long calls = KnkBlockExaminer.CALLS.sum();
        long blocked = KnkBlockExaminer.BLOCKED.sum();
        KnkBlockExaminer examiner = new KnkBlockExaminer(leg(GATE_AT_5), List.of(permissive()));

        examiner.isPassable(mock(BlockSource.class), at(5, 1, 0));
        examiner.isPassable(mock(BlockSource.class), at(4, 1, 0));

        assertTrue(KnkBlockExaminer.CALLS.sum() >= calls + 2);
        assertTrue(KnkBlockExaminer.BLOCKED.sum() >= blocked + 1);
    }

    @Test
    void examinersCitizensAddsAfterOursAreRemovedAndNamed() {
        KnkBlockExaminer ours = new KnkBlockExaminer(leg(CellAccess.OPEN), List.of(permissive()));
        BlockExaminer foreign = permissive();
        NavigatorParameters local = new NavigatorParameters();
        local.examiner(ours);
        local.examiner(foreign);

        String names = CitizensNpcMover.stripForeignExaminers(local, ours);

        assertTrue(names != null && !names.isEmpty());
        assertArrayEquals(new BlockExaminer[] {ours}, local.examiners());
        assertNull(CitizensNpcMover.stripForeignExaminers(local, ours), "nothing foreign left: no leak reported");
        assertFalse(CitizensNpcMover.isCitizensCostOnly(foreign));
    }
}
