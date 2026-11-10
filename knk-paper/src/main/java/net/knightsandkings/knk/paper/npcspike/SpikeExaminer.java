package net.knightsandkings.knk.paper.npcspike;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;

/**
 * #243 spike (throwaway, never merged): the one block examiner a spike NPC's Citizens A* search uses.
 *
 * <p>Why a composite: Citizens combines examiners "first PASSABLE / first STANDABLE wins"
 * ({@code VectorNode#isPassable}), so an extra examiner answering IMPASSABLE cannot veto a later
 * {@code MinecraftBlockExaminer} that answers PASSABLE. The K&K rules therefore sit <em>in front of</em> the
 * Citizens examiners: a cell the K&K {@link CellAccess} blocks (closed gate, denied domain, a door the mover
 * may not open) is NOT_STANDABLE and IMPASSABLE before any inner examiner is asked; every other cell is
 * decided by the inner examiners, so ladder and door callbacks still attach.
 *
 * <p>Cells outside the capture box are blocked: the access verdicts were only decided inside it.
 *
 * <p>Thread-safety: with {@code CITIZENS_ASYNC} this runs on Citizens' worker pool. {@link CellAccess}
 * from the walk preparer is immutable; the counters are {@link LongAdder}s.
 */
final class SpikeExaminer implements BlockExaminer {

    static final LongAdder CALLS = new LongAdder();
    static final LongAdder NANOS = new LongAdder();
    static final LongAdder BLOCKED = new LongAdder();

    private final CellAccess access;
    private final WalkBox box;
    private final List<BlockExaminer> inner;

    SpikeExaminer(CellAccess access, WalkBox box, List<BlockExaminer> inner) {
        this.access = access;
        this.box = box;
        this.inner = List.copyOf(inner);
    }

    private boolean blocked(PathPoint point) {
        long start = System.nanoTime();
        int x = point.getVector().getBlockX();
        int feetY = point.getVector().getBlockY();
        int z = point.getVector().getBlockZ();
        boolean blocked = box != null && !box.contains(x, feetY, z) || !access.allows(x, feetY, z);
        CALLS.increment();
        NANOS.add(System.nanoTime() - start);
        if (blocked) {
            BLOCKED.increment();
        }
        return blocked;
    }

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        if (blocked(point)) {
            return StandableState.NOT_STANDABLE;
        }
        for (BlockExaminer examiner : inner) {
            StandableState state = examiner.canStandAt(source, point);
            if (state != StandableState.IGNORE) {
                return state;
            }
        }
        return StandableState.IGNORE;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        if (blocked(point)) {
            return PassableState.IMPASSABLE;
        }
        PassableState result = PassableState.IGNORE;
        for (BlockExaminer examiner : inner) {
            PassableState state = examiner.isPassable(source, point);
            if (state == PassableState.PASSABLE) {
                return state;
            }
            if (state == PassableState.IMPASSABLE) {
                result = state;
            }
        }
        return result;
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        float cost = 0F;
        for (BlockExaminer examiner : inner) {
            cost += examiner.getCost(source, point);
        }
        double extra = access.extraCost(point.getVector().getBlockX(), point.getVector().getBlockY(),
            point.getVector().getBlockZ());
        return Double.isFinite(extra) ? cost + (float) extra : cost;
    }
}
