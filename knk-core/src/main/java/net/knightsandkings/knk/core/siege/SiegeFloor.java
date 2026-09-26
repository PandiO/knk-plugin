package net.knightsandkings.knk.core.siege;

import java.util.OptionalDouble;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/**
 * Snaps a capture point to the floor it stands on (playtest 2026-09-26): a point captured a block or
 * two in the air (or inside the clicked block) draws its ring, and measures capture distance, from the
 * walkable surface below instead. Pure: the caller answers per block height whether the block in that
 * column is solid, and where its collision top is.
 */
public final class SiegeFloor {
    private SiegeFloor() {}

    /** How far below the point a floor is searched for. */
    public static final int MAX_DROP = 4;
    /** How far a point inside a solid block is lifted to reach open space. */
    public static final int MAX_LIFT = 3;

    /**
     * @param y       the point's height
     * @param solidAt whether the block at a block-y in the point's column has collision
     * @param topAt   the absolute top of that block's collision box (e.g. {@code by + 1}, {@code by + 0.5} for a slab)
     * @return the floor height, or empty when there's no floor within reach (keep the point as is)
     */
    public static OptionalDouble floorY(double y, IntPredicate solidAt, IntToDoubleFunction topAt) {
        int by = (int) Math.floor(y);
        // A point inside a solid block (e.g. captured on a clicked block, or standing on a slab):
        // step up to the first open block.
        int lifted = 0;
        while (solidAt.test(by)) {
            if (++lifted > MAX_LIFT) return OptionalDouble.empty();
            by++;
        }
        for (int below = by - 1; below >= by - MAX_DROP; below--) {
            if (solidAt.test(below)) return OptionalDouble.of(topAt.applyAsDouble(below));
        }
        return OptionalDouble.empty();
    }
}
