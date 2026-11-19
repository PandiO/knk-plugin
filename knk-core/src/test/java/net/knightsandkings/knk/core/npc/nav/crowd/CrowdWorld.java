package net.knightsandkings.knk.core.npc.nav.crowd;

import java.util.HashSet;
import java.util.Set;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;

/** A test world for crowd tests: a flat floor at 64, walls two blocks high, everything loaded; it counts reads. */
final class CrowdWorld implements NavWorld {

    static final String W = "w";

    final Set<Long> walls = new HashSet<>();
    /** Columns whose floor is a block higher (65): a wall top or a ledge beside the way, with room above it. */
    final Set<Long> ledges = new HashSet<>();
    /** A floor block's top where it is not a whole block ("x,y,z" to 0..1), as {@code FakeNavWorld}. */
    final java.util.Map<String, Double> tops = new java.util.HashMap<>();
    long reads;

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** Walls on every column from (x1, z1) to (x2, z2), both included. */
    CrowdWorld walls(int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                walls.add(key(x, z));
            }
        }
        return this;
    }

    /** A ledge (floor at 65) on every column from (x1, z1) to (x2, z2), both included. */
    CrowdWorld ledges(int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                ledges.add(key(x, z));
            }
        }
        return this;
    }

    /** A wall along x = {@code x} from z 0 to 20 with a one-block doorway at z = {@code door}. */
    static CrowdWorld doorway(int x, int door) {
        CrowdWorld w = new CrowdWorld().walls(x, 0, x, door - 1).walls(x, door + 1, x, 20);
        return w;
    }

    /** A 1-wide passage along z = {@code z} from x1 to x2 (walls at z - 1 and z + 1). */
    static CrowdWorld passage(int x1, int x2, int z) {
        return new CrowdWorld().walls(x1, z - 1, x2, z - 1).walls(x1, z + 1, x2, z + 1);
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        reads++;
        return true;
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        reads++;
        return floorY == (ledges.contains(key(x, z)) ? 65 : 64) && !walls.contains(key(x, z));
    }

    @Override
    public double freeHeight(String world, int x, int floorY, int z) {
        reads++;
        return isStandableFloor(world, x, floorY, z) ? 3.0 : 0.0;
    }

    @Override
    public double surfaceY(String world, double x, int floorY, double z) {
        return floorY + tops.getOrDefault((int) Math.floor(x) + "," + floorY + "," + (int) Math.floor(z), 1.0);
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        return Set.of();
    }

    @Override
    public boolean blocksBody(String world, int x, int y, int z) {
        reads++;
        return walls.contains(key(x, z)) && (y == 65 || y == 66) || ledges.contains(key(x, z)) && y == 65;
    }

    static NavPoint feet(double x, double z) {
        return new NavPoint(W, x, 65, z);
    }
}
