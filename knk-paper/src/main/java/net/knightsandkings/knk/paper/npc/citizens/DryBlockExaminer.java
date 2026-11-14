package net.knightsandkings.knk.paper.npc.citizens;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;

/**
 * The path search of an NPC spawned {@code dry} (the Phase 1 test walkers, PandiO/knk-workspace#490): never through
 * or over water or lava. Citizens' {@link MinecraftBlockExaminer} counts a point in or over a liquid as STANDABLE and a
 * liquid as PASSABLE, so a walker sent to a dry target across a pond took the pond, sank (a player-shaped NPC doesn't
 * float) and stood on the bottom drowning; Citizens' protection cancels the damage, so it never died (the audit after
 * the second #490 commit: {@code EntityDamageEvent 1088, causes: DROWNING 1088}, {@code EntityAirChangeEvent 29310}).
 *
 * <p>Like {@link KnkBlockExaminer}, it sits <em>in front of</em> the Citizens examiners, because Citizens combines
 * examiners "first PASSABLE / first STANDABLE wins" and a later IMPASSABLE could not veto. A wet cell is NOT_STANDABLE
 * and IMPASSABLE; every other cell is decided by the inner examiners. The search's start point (no parent) is left to
 * the inner examiners, so a walker already standing in water can still path out of it.
 *
 * <p>Wet: a liquid or waterlogged block, and the blocks that hold water without being {@link
 * Waterlogged} (seagrass, kelp, bubble columns). For the body (feet and head) and for the floor
 * (standing "on" water means sinking into it). Thread-safety: stateless; may run on Citizens' worker pool.
 */
final class DryBlockExaminer implements BlockExaminer {

    /** Blocks that are always under water but neither {@code WATER} nor waterloggable. */
    static final Set<Material> ALWAYS_WET = EnumSet.of(Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP,
        Material.KELP_PLANT, Material.BUBBLE_COLUMN);

    private final List<BlockExaminer> inner;

    DryBlockExaminer(List<BlockExaminer> inner) {
        this.inner = List.copyOf(inner);
    }

    static boolean wet(Material material, BlockData data) {
        return material != null && (liquid(material) || ALWAYS_WET.contains(material)
            || data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged());
    }

    static boolean liquid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    private static boolean wetAt(BlockSource source, int x, int y, int z) {
        return wet(source.getMaterialAt(x, y, z), source.getBlockDataAt(x, y, z));
    }

    /** Feet, head or floor of a body standing at {@code point} is wet. */
    static boolean wetBody(BlockSource source, PathPoint point) {
        Vector v = point.getVector();
        int x = v.getBlockX();
        int y = v.getBlockY();
        int z = v.getBlockZ();
        // The floor counts only as a liquid: a waterlogged slab or stair is a dry floor to stand on.
        return wetAt(source, x, y, z) || wetAt(source, x, y + 1, z)
            || liquid(source.getMaterialAt(x, y - 1, z));
    }

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        if (point.getParentPoint() != null && wetBody(source, point)) {
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
        Vector v = point.getVector();
        if (point.getParentPoint() != null && wetAt(source, v.getBlockX(), v.getBlockY(), v.getBlockZ())) {
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
        return cost;
    }
}
