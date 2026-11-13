package net.knightsandkings.knk.core.npc.nav;

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
}
