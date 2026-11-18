package net.knightsandkings.knk.core.navigation.shape;

import java.util.Set;

/**
 * The world queries NPC navigation needs from the engine (decision Q1: a port, no engine types). Main thread; an
 * implementation never loads a chunk to answer.
 */
public interface NavWorld {

    /** Whether the chunk of this block column is loaded. */
    boolean isLoaded(String world, int x, int z);

    /**
     * Whether an NPC can stand on the block at {@code (x, floorY, z)}: a solid block that is not foliage, with two
     * passable blocks above it. False in an unloaded chunk and outside the world's height.
     */
    boolean isStandableFloor(String world, int x, int floorY, int z);

    /** The WorldGuard regions at a feet position (never {@code __global__}); empty when unknown. */
    Set<String> regionsAt(String world, int x, int feetY, int z);

    /**
     * The free height, in blocks, between the top of the floor at {@code (x, floorY, z)} and the first block above it
     * that a body cannot pass (implementation plan 4b.2 item 4: a slab in the upper half of the third block above a
     * full floor gives 2.5). 0 when the floor is not standable or the chunk is not loaded. The default, for worlds that
     * cannot tell, is 3 for a standable floor.
     */
    default double freeHeight(String world, int x, int floorY, int z) {
        return isStandableFloor(world, x, floorY, z) ? 3.0 : 0.0;
    }

    /**
     * Whether the block at {@code (x, y, z)} stops a walking body in that cell (a wall, a fence, a full block); a step
     * the body walks up (a bottom slab, a stair) does not (implementation plan 4b.2 items 1 and 13). False in an
     * unloaded chunk. The default, for worlds that cannot tell, knows no walls.
     */
    default boolean blocksBody(String world, int x, int y, int z) {
        return false;
    }

    /**
     * The walking surface of the floor block at {@code floorY} where a body stands at {@code (x, z)} (link B4,
     * PandiO/knk-workspace#673): the top of the block's step, so a bottom slab and an upright stair give
     * {@code floorY + 0.5} and a full block {@code floorY + 1}. A stair's upper half is not counted: it is walked up
     * from the step, while a waypoint a whole block above the feet makes the engine jump. The default, for worlds that
     * cannot tell, is the whole block: {@code floorY + 1}.
     */
    default double surfaceY(String world, double x, int floorY, double z) {
        return floorY + 1;
    }
}
