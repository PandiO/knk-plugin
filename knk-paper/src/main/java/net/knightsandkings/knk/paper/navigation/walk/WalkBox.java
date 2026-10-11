package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The block box one walk search may use (KNG-51 {@code LAST_MILE_PATHFINDING.md} §8 "Which chunks"):
 * the bounding box of start and target expanded by {@code capture-margin} on every side, up and down
 * included (the search climbs ladders and drops; it never needs more than the margin above or below
 * the leg). Bounds inclusive, block coordinates; clamped to the world's height.
 */
public record WalkBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public WalkBox {
        Objects.requireNonNull(world, "world");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min corner must be <= max corner");
        }
    }

    /**
     * The box around a leg: feet position of the mover and the target's floor position (block
     * coordinates are the floors of the doubles), expanded by {@code margin}, clamped to
     * {@code worldMinY..worldMaxY - 1}.
     */
    public static WalkBox around(String world, double startX, double startFeetY, double startZ, double targetX,
                                 double targetFloorY, double targetZ, int margin, int worldMinY, int worldMaxY) {
        if (margin < 0) {
            throw new IllegalArgumentException("margin must not be negative: " + margin);
        }
        int sx = (int) Math.floor(startX);
        int sy = (int) Math.floor(startFeetY) - 1;
        int sz = (int) Math.floor(startZ);
        int tx = (int) Math.floor(targetX);
        int ty = (int) Math.floor(targetFloorY);
        int tz = (int) Math.floor(targetZ);
        int minY = Math.max(worldMinY, Math.min(sy, ty) - margin);
        int maxY = Math.min(worldMaxY - 1, Math.max(sy, ty) + 1 + margin);
        if (minY > maxY) {
            minY = maxY = Math.max(worldMinY, Math.min(worldMaxY - 1, ty));
        }
        return new WalkBox(world, Math.min(sx, tx) - margin, minY, Math.min(sz, tz) - margin,
            Math.max(sx, tx) + margin, maxY, Math.max(sz, tz) + margin);
    }

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    /** Lowest section ({@code y >> 4}) the box touches. */
    public int minSection() {
        return minY >> 4;
    }

    public int maxSection() {
        return maxY >> 4;
    }

    public int chunkCount() {
        return (maxChunkX() - minChunkX() + 1) * (maxChunkZ() - minChunkZ() + 1);
    }

    /** The chunk keys ({@link WalkChunk#key}) the box touches, x-major. */
    public List<Long> chunkKeys() {
        List<Long> keys = new ArrayList<>(chunkCount());
        for (int cx = minChunkX(); cx <= maxChunkX(); cx++) {
            for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) {
                keys.add(WalkChunk.key(cx, cz));
            }
        }
        return keys;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
