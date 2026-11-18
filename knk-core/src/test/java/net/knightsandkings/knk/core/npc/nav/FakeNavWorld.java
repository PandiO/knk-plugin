package net.knightsandkings.knk.core.npc.nav;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;

/** A test world: one floor height per column (plus extra floors), everything loaded unless told otherwise. */
final class FakeNavWorld implements NavWorld {

    static final String WORLD = "world";

    IntBinaryOperator floor = (x, z) -> 64;
    BiPredicate<Integer, Integer> loaded = (x, z) -> true;
    final Set<String> extraFloors = new HashSet<>();
    Set<String> regions = Set.of();
    /** Regions per block column; null: {@link #regions} everywhere. */
    java.util.function.BiFunction<Integer, Integer, Set<String>> regionsByColumn;

    /** Blocks that stop a body (walls), as "x,y,z". */
    final Set<String> walls = new HashSet<>();
    /** Free height above a floor, as "x,y,z" of the floor; others have 3. */
    final java.util.Map<String, Double> headroom = new java.util.HashMap<>();
    /** The top of a floor block under a body, as "x,y,z" of the floor (a bottom slab: 0.5); others are whole (1). */
    final java.util.Map<String, Double> tops = new java.util.HashMap<>();
    /** Floors that are not standable although {@link #floor} says so (air columns, a drop). */
    final Set<String> holes = new HashSet<>();

    void addFloor(int x, int y, int z) {
        extraFloors.add(x + "," + y + "," + z);
    }

    /** A wall two blocks high (feet and head) standing on the floor at {@code floorY}. */
    void addWall(int x, int floorY, int z) {
        walls.add(x + "," + (floorY + 1) + "," + z);
        walls.add(x + "," + (floorY + 2) + "," + z);
    }

    @Override
    public boolean blocksBody(String world, int x, int y, int z) {
        return walls.contains(x + "," + y + "," + z);
    }

    @Override
    public double freeHeight(String world, int x, int floorY, int z) {
        if (!isStandableFloor(world, x, floorY, z)) {
            return 0;
        }
        return headroom.getOrDefault(x + "," + floorY + "," + z, 3.0);
    }

    @Override
    public double surfaceY(String world, double x, int floorY, double z) {
        return floorY + tops.getOrDefault((int) Math.floor(x) + "," + floorY + "," + (int) Math.floor(z), 1.0);
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        return loaded.test(x, z);
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        String key = x + "," + floorY + "," + z;
        return loaded.test(x, z) && !holes.contains(key)
            && (floor.applyAsInt(x, z) == floorY || extraFloors.contains(key))
            && !walls.contains(x + "," + (floorY + 1) + "," + z) && !walls.contains(x + "," + (floorY + 2) + "," + z);
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        return regionsByColumn == null ? regions : regionsByColumn.apply(x, z);
    }

    static NavPoint floor(double x, double y, double z) {
        return new NavPoint(WORLD, x, y, z);
    }
}
