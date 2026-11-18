package net.knightsandkings.knk.paper.npc.citizens;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;

/**
 * The one block examiner a K&K NPC's Citizens path search uses (BEHAVIOR_MODEL NAV-01).
 *
 * <p>Why a composite: Citizens combines examiners "first PASSABLE / first STANDABLE wins", so an extra examiner
 * answering IMPASSABLE cannot veto a later {@code MinecraftBlockExaminer} that answers PASSABLE. K&K's rules
 * therefore sit <em>in front of</em> the Citizens examiners: a cell the {@link LocalLeg} blocks (a closed gate, a
 * denied region, a door the mover may not open, anything outside the captured box) is NOT_STANDABLE and IMPASSABLE
 * before any inner examiner is asked; every other cell is decided by the inner examiners, so ladder and door
 * callbacks still attach.
 *
 * <p>Thread-safety: with {@code citizens_async} this runs on Citizens' worker pool. The leg is immutable; the
 * counters are {@link LongAdder}s.
 */
final class KnkBlockExaminer implements BlockExaminer {

    /** Cells asked and cells K&K blocked, for {@code /knk npc nav stats}. */
    static final LongAdder CALLS = new LongAdder();
    static final LongAdder BLOCKED = new LongAdder();

    private final LocalLeg leg;
    private final List<BlockExaminer> inner;

    KnkBlockExaminer(LocalLeg leg, List<BlockExaminer> inner) {
        this.leg = leg;
        this.inner = List.copyOf(inner);
    }

    private boolean blocked(PathPoint point) {
        boolean blocked = !leg.allows(point.getVector().getBlockX(), point.getVector().getBlockY(),
            point.getVector().getBlockZ());
        CALLS.increment();
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
        double extra = leg.access().extraCost(point.getVector().getBlockX(), point.getVector().getBlockY(),
            point.getVector().getBlockZ());
        return Double.isFinite(extra) ? cost + (float) extra : cost;
    }
}
