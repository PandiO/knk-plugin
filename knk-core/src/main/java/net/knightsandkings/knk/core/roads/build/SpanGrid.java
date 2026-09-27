package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.util.BlockKey;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The span grid (DESIGN §5.2): the world seen as road <em>spans</em> — cells a player can stand on
 * whose floor is a road material — with at most one link per span in each of the 8 horizontal
 * directions. It is a pure view over the {@link SurfaceGrid}: nothing is stored, every answer is
 * computed on demand from the world, so the mask BFS can ask about cells it has not seen yet.
 *
 * <p>A <b>span</b> is the floor block {@code (x, y, z)}: solid, a road material of some applicable
 * profile (looking through overlays), not a hazard, with {@value #HEADROOM} passable, hazard-free
 * blocks above it. Gate-door blocks ({@link GateCells}) count as passable headroom whatever the
 * gate's state (DESIGN §5.4). Because passable and solid are exact complements
 * ({@link PassabilityRules}), spans at y-1, y and y+1 of one column exclude each other pairwise,
 * which is what guarantees "at most one neighbour per direction".
 *
 * <p><b>Links</b> (symmetric): a span links to the span at dy ∈ {-1, 0, +1} in the neighbouring
 * column when either both are level, or the step is allowed — the lower span has a third passable
 * block above it (room to jump) or the upper span is a stair or slab. A <b>diagonal</b> link
 * additionally needs an L-shaped path through one of the two flanking orthogonal spans, so roads
 * touching at a corner do not join.
 *
 * <p>Directions: {@link #N} … {@link #NW} clockwise from north (dz = -1), which is the P2 … P9 order
 * Zhang-Suen thinning expects. Coordinates of a span are its floor block; a player standing on it has
 * their feet at {@code y + 1}.
 */
public final class SpanGrid {

    public static final int N = 0;
    public static final int NE = 1;
    public static final int E = 2;
    public static final int SE = 3;
    public static final int S = 4;
    public static final int SW = 5;
    public static final int W = 6;
    public static final int NW = 7;
    public static final int DIRECTIONS = 8;

    /** x offset per direction (E = +x). */
    public static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    /** z offset per direction (S = +z, N = -z). */
    public static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    /** {@link #neighbourDy} result when there is no link in that direction. */
    public static final int NO_LINK = Integer.MIN_VALUE;

    /** Passable blocks a span needs above its floor (a player is just under two blocks tall). */
    public static final int HEADROOM = 2;

    private static final int[] DY_CANDIDATES = {0, -1, 1};

    private final SurfaceGrid grid;
    private final ProfileSet profiles;
    private final GateCells gates;

    public SpanGrid(SurfaceGrid grid, ProfileSet profiles, GateCells gates) {
        this.grid = Objects.requireNonNull(grid, "grid");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.gates = Objects.requireNonNull(gates, "gates");
    }

    /** The opposite of a direction ({@code N ↔ S}, {@code NE ↔ SW} …). */
    public static int opposite(int dir) {
        return (dir + 4) & 7;
    }

    /** Whether a direction is one of the four diagonals. */
    public static boolean isDiagonal(int dir) {
        return (dir & 1) == 1;
    }

    public SurfaceGrid surface() {
        return grid;
    }

    public ProfileSet profiles() {
        return profiles;
    }

    /** A block a player can move through: passable in the world, or part of a gate door's closed footprint. */
    public boolean isPassable(int x, int y, int z) {
        return grid.isPassable(x, y, z) || gates.doorAt(x, y, z).isPresent();
    }

    /** Whether the floor block {@code (x, y, z)} is a road span. */
    public boolean isSpan(int x, int y, int z) {
        if (y < grid.minY() || y + HEADROOM >= grid.maxY()) {
            return false;
        }
        if (!grid.isSolid(x, y, z) || grid.isHazard(x, y, z)) {
            return false;
        }
        for (int h = 1; h <= HEADROOM; h++) {
            if (!isPassable(x, y + h, z) || grid.isHazard(x, y + h, z)) {
                return false;
            }
        }
        return profiles.isRoadMaterial(grid.floorMaterial(x, y, z), x, z);
    }

    /** Whether the packed floor block is a road span. */
    public boolean isSpan(long key) {
        return isSpan(BlockKey.x(key), BlockKey.y(key), BlockKey.z(key));
    }

    /** The floor material of a span (looking through overlays). */
    public String floorMaterial(long key) {
        return grid.floorMaterial(BlockKey.x(key), BlockKey.y(key), BlockKey.z(key));
    }

    /** Whether a span's floor material is ambiguous in its column (DESIGN §5.1). */
    public boolean isAmbiguous(long key) {
        int x = BlockKey.x(key);
        int z = BlockKey.z(key);
        return profiles.isAmbiguous(grid.floorMaterial(x, BlockKey.y(key), z), x, z);
    }

    /**
     * The gate door whose closed footprint covers the span's floor or headroom, if any — the spans
     * under a door are tagged with it (DESIGN §5.4).
     */
    public OptionalInt gateDoor(long key) {
        int x = BlockKey.x(key);
        int y = BlockKey.y(key);
        int z = BlockKey.z(key);
        for (int h = 0; h <= HEADROOM; h++) {
            OptionalInt door = gates.doorAt(x, y + h, z);
            if (door.isPresent()) {
                return door;
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The height difference to the linked span in a direction: {@code -1}, {@code 0} or {@code +1},
     * or {@link #NO_LINK} when the span has no link that way. Allocation-free; the linked key is
     * {@code BlockKey.neighbour(key, DX[dir], dy, DZ[dir])}. Assumes {@code key} is a span.
     */
    public int neighbourDy(long key, int dir) {
        int x = BlockKey.x(key);
        int y = BlockKey.y(key);
        int z = BlockKey.z(key);
        int nx = x + DX[dir];
        int nz = z + DZ[dir];
        int dy = NO_LINK;
        for (int candidate : DY_CANDIDATES) {
            if (isSpan(nx, y + candidate, nz)) {
                dy = candidate;
                break;
            }
        }
        if (dy == NO_LINK) {
            return NO_LINK;
        }
        if (isDiagonal(dir)) {
            long target = BlockKey.pack(nx, y + dy, nz);
            return cornerPath(key, dir, target) ? dy : NO_LINK;
        }
        return stepAllowed(x, y, z, nx, y + dy, nz) ? dy : NO_LINK;
    }

    /** The linked span in a direction, if any. Assumes {@code key} is a span. */
    public OptionalLong neighbour(long key, int dir) {
        int dy = neighbourDy(key, dir);
        if (dy == NO_LINK) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(BlockKey.neighbour(key, DX[dir], dy, DZ[dir]));
    }

    /**
     * Whether a player can walk between two adjacent spans that differ in height by one: the lower
     * span needs a third passable block above it (room to jump), unless the upper one is a stair or
     * slab (DESIGN §5.2). Level moves are always allowed.
     */
    private boolean stepAllowed(int ax, int ay, int az, int bx, int by, int bz) {
        if (ay == by) {
            return true;
        }
        boolean aLower = ay < by;
        int lx = aLower ? ax : bx;
        int ly = aLower ? ay : by;
        int lz = aLower ? az : bz;
        int ux = aLower ? bx : ax;
        int uy = aLower ? by : ay;
        int uz = aLower ? bz : az;
        if (grid.isStairOrSlab(ux, uy, uz)) {
            return true;
        }
        int jumpY = ly + HEADROOM + 1;
        return jumpY < grid.maxY() && isPassable(lx, jumpY, lz) && !grid.isHazard(lx, jumpY, lz);
    }

    /**
     * A diagonal link needs an L-shaped walk through one of the two flanking orthogonal spans that
     * ends exactly on the target (so the total height difference is also within one block).
     */
    private boolean cornerPath(long from, int diagonal, long target) {
        int first = (diagonal + 7) & 7;  // e.g. NE → N
        int second = (diagonal + 1) & 7; // e.g. NE → E
        return viaCorner(from, first, second, target) || viaCorner(from, second, first, target);
    }

    private boolean viaCorner(long from, int firstDir, int secondDir, long target) {
        int dy1 = neighbourDy(from, firstDir);
        if (dy1 == NO_LINK) {
            return false;
        }
        long corner = BlockKey.neighbour(from, DX[firstDir], dy1, DZ[firstDir]);
        int dy2 = neighbourDy(corner, secondDir);
        if (dy2 == NO_LINK) {
            return false;
        }
        return BlockKey.neighbour(corner, DX[secondDir], dy2, DZ[secondDir]) == target;
    }
}
