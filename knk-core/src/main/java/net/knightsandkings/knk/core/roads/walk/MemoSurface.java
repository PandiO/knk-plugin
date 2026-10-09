package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * A per-search memo over a {@link SurfaceGrid} and its {@link WalkCells}: the walk search asks about the same blocks many times
 * (every cell test reads its floor and headroom, and each neighbour is tested from up to eight sides),
 * so every flag of a block (passable, solid, hazard, stair/slab and the {@link WalkCells} door,
 * climbable and water flags) and its floor material are read from the terrain once. Not thread-safe;
 * one instance per {@link WalkSearch#find} call.
 */
final class MemoSurface implements SurfaceGrid {

    private static final short PASSABLE = 1;
    private static final short SOLID = 2;
    private static final short HAZARD = 4;
    private static final short STAIR = 8;
    private static final short DOOR = 16;
    private static final short CLIMBABLE = 32;
    private static final short WATER = 64;

    private final SurfaceGrid grid;
    private final WalkCells walkCells;
    private final LongMap<Short> flags = new LongMap<>(4096);
    private final LongMap<String> floors = new LongMap<>(1024);

    MemoSurface(SurfaceGrid grid, WalkCells walkCells) {
        this.grid = grid;
        this.walkCells = walkCells;
    }

    /** The memoised {@link WalkCells} view of the same terrain. */
    WalkCells cells() {
        return new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return (flags(x, y, z) & DOOR) != 0;
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return (flags(x, y, z) & CLIMBABLE) != 0;
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return (flags(x, y, z) & WATER) != 0;
            }
        };
    }

    private short flags(int x, int y, int z) {
        long key = BlockKey.pack(x, y, z);
        Short cached = flags.get(key);
        if (cached != null) {
            return cached;
        }
        short f = 0;
        if (grid.isPassable(x, y, z)) {
            f |= PASSABLE;
        }
        if (grid.isSolid(x, y, z)) {
            f |= SOLID;
        }
        if (grid.isHazard(x, y, z)) {
            f |= HAZARD;
        }
        if (grid.isStairOrSlab(x, y, z)) {
            f |= STAIR;
        }
        if (walkCells.isDoor(x, y, z)) {
            f |= DOOR;
        }
        if (walkCells.isClimbable(x, y, z)) {
            f |= CLIMBABLE;
        }
        if (walkCells.isWater(x, y, z)) {
            f |= WATER;
        }
        flags.put(key, f);
        return f;
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        return (flags(x, y, z) & PASSABLE) != 0;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return (flags(x, y, z) & SOLID) != 0;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return (flags(x, y, z) & HAZARD) != 0;
    }

    @Override
    public boolean isStairOrSlab(int x, int y, int z) {
        return (flags(x, y, z) & STAIR) != 0;
    }

    @Override
    public String floorMaterial(int x, int y, int z) {
        long key = BlockKey.pack(x, y, z);
        String floor = floors.get(key);
        if (floor == null) {
            floor = grid.floorMaterial(x, y, z);
            floors.put(key, floor);
        }
        return floor;
    }

    @Override
    public int minY() {
        return grid.minY();
    }

    @Override
    public int maxY() {
        return grid.maxY();
    }
}
