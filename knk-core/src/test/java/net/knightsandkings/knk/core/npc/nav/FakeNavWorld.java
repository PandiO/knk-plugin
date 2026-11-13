package net.knightsandkings.knk.core.npc.nav;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;

/** A test world: one floor height per column (plus extra floors), everything loaded unless told otherwise. */
final class FakeNavWorld implements NavWorld {

    static final String WORLD = "world";

    IntBinaryOperator floor = (x, z) -> 64;
    BiPredicate<Integer, Integer> loaded = (x, z) -> true;
    final Set<String> extraFloors = new HashSet<>();
    Set<String> regions = Set.of();
    /** Regions per block column; null: {@link #regions} everywhere. */
    java.util.function.BiFunction<Integer, Integer, Set<String>> regionsByColumn;

    void addFloor(int x, int y, int z) {
        extraFloors.add(x + "," + y + "," + z);
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        return loaded.test(x, z);
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        return loaded.test(x, z) && (floor.applyAsInt(x, z) == floorY || extraFloors.contains(x + "," + floorY + "," + z));
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        return regionsByColumn == null ? regions : regionsByColumn.apply(x, z);
    }

    static NavPoint floor(double x, double y, double z) {
        return new NavPoint(WORLD, x, y, z);
    }
}
