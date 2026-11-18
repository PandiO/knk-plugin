package net.knightsandkings.knk.core.navigation.shape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A test world for corridor tests: one floor per column (64 unless set), walls two blocks high, holes (a drop), regions
 * per column, everything loaded; it counts every read. Shared by the shaping tests and the NPC local-leg tests
 * (PandiO/knk-workspace#704).
 */
public final class GridWorld implements NavWorld {

    public static final String W = "w";

    public final Map<Long, Integer> floors = new HashMap<>();
    public final Set<Long> walls = new HashSet<>();
    public final Set<Long> holes = new HashSet<>();
    public final Map<Long, Set<String>> regions = new HashMap<>();
    public long reads;

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public int floor(int x, int z) {
        return floors.getOrDefault(key(x, z), 64);
    }

    public GridWorld wall(int x, int z) {
        walls.add(key(x, z));
        return this;
    }

    /** Walls on every column from (x1, z1) to (x2, z2), both included. */
    public GridWorld walls(int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                wall(x, z);
            }
        }
        return this;
    }

    public GridWorld hole(int x, int z) {
        holes.add(key(x, z));
        return this;
    }

    public GridWorld floorAt(int x, int z, int y) {
        floors.put(key(x, z), y);
        return this;
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        reads++;
        return true;
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        reads++;
        long k = key(x, z);
        return !walls.contains(k) && !holes.contains(k) && floor(x, z) == floorY;
    }

    @Override
    public double freeHeight(String world, int x, int floorY, int z) {
        reads++;
        long k = key(x, z);
        return !walls.contains(k) && !holes.contains(k) && floor(x, z) == floorY ? 3.0 : 0.0;
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        reads++;
        return regions.getOrDefault(key(x, z), Set.of());
    }

    @Override
    public boolean blocksBody(String world, int x, int y, int z) {
        reads++;
        return walls.contains(key(x, z)) && (y == floor(x, z) + 1 || y == floor(x, z) + 2);
    }

    /** Feet at a position on its column's floor. */
    public NavPoint feet(double x, double z) {
        return new NavPoint(W, x, floor((int) Math.floor(x), (int) Math.floor(z)) + 1, z);
    }
}
