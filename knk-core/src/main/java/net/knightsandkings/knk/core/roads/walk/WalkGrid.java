package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.util.BlockKey;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The walkability core shared by the road builder and the last-mile walk search (KNG-51
 * {@code LAST_MILE_PATHFINDING.md} §3, §4): the world seen as <em>cells</em> a mover can stand on,
 * with at most one link per cell in each of the 8 horizontal directions. Extracted unchanged from
 * {@code roads/build/SpanGrid}, which now delegates here and adds only its road-material test as the
 * {@link FloorTest}. It is a pure view over the {@link SurfaceGrid}: nothing is stored, every answer
 * is computed on demand.
 *
 * <p>A <b>cell</b> is the floor block {@code (x, y, z)}: solid, accepted by the {@link FloorTest}
 * (looking through overlays is the test's business), not a hazard, with {@code headroom} passable,
 * hazard-free blocks above it. Gate-door blocks ({@link GateCells}) count as passable headroom
 * whatever the gate's state (road DESIGN §5.4). Because passable and solid are exact complements,
 * cells at y-1, y and y+1 of one column exclude each other pairwise, which is what guarantees "at
 * most one neighbour per direction".
 *
 * <p><b>Links</b> (symmetric): a cell links to the cell at dy ∈ {-1, 0, +1} in the neighbouring
 * column when either both are level, or the step is allowed — the lower cell has a passable block
 * above its headroom (room to jump) or the upper cell is a stair or slab. A <b>diagonal</b> link
 * additionally needs an L-shaped path through one of the two flanking orthogonal cells, so corners
 * of walls are not cut.
 *
 * <p>Directions: {@link #N} … {@link #NW} clockwise from north (dz = -1). Coordinates of a cell are
 * its floor block; a mover standing on it has their feet at {@code y + 1}.
 */
public final class WalkGrid {

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

    private static final int[] DY_CANDIDATES = {0, -1, 1};

    /** Which solid, hazard-free floor blocks may carry a cell (the road builder: road materials only). */
    @FunctionalInterface
    public interface FloorTest {
        /** Every solid block is a floor. */
        FloorTest ANY = (grid, x, y, z) -> true;

        /** Whether the solid, hazard-free block {@code (x, y, z)} with clear headroom is a floor. */
        boolean accepts(SurfaceGrid grid, int x, int y, int z);
    }

    private final SurfaceGrid grid;
    private final GateCells gates;
    private final WalkCells cells;
    private final int headroom;
    private final FloorTest floor;

    /**
     * The road builder's view: no doors or climbables ({@link WalkCells#NONE}).
     *
     * @param headroom passable blocks a cell needs above its floor (a player: 2)
     * @param floor    which floors carry cells
     */
    public WalkGrid(SurfaceGrid grid, GateCells gates, int headroom, FloorTest floor) {
        this(grid, gates, WalkCells.NONE, headroom, floor);
    }

    /**
     * The walk search's view: door and climbable blocks ({@link WalkCells}) also count as passable,
     * like gate footprints — whether a mover may pass a door is the cell's access verdict.
     *
     * @param headroom passable blocks a cell needs above its floor ({@link MovementProfile#headroom})
     * @param floor    which floors carry cells
     */
    public WalkGrid(SurfaceGrid grid, GateCells gates, WalkCells cells, int headroom, FloorTest floor) {
        this.grid = Objects.requireNonNull(grid, "grid");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.floor = Objects.requireNonNull(floor, "floor");
        if (headroom < 1) {
            throw new IllegalArgumentException("headroom must be at least 1: " + headroom);
        }
        this.headroom = headroom;
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

    public int headroom() {
        return headroom;
    }

    /**
     * A block a mover can move through: passable in the world, part of a gate door's closed footprint,
     * or (walk view only) a hand-openable door or a climbable.
     */
    public boolean isPassable(int x, int y, int z) {
        return grid.isPassable(x, y, z) || gates.doorAt(x, y, z).isPresent()
            || cells.isDoor(x, y, z) || cells.isClimbable(x, y, z);
    }

    /** Whether the floor block {@code (x, y, z)} is a cell. */
    public boolean isCell(int x, int y, int z) {
        if (y < grid.minY() || y + headroom >= grid.maxY()) {
            return false;
        }
        if (!grid.isSolid(x, y, z) || grid.isHazard(x, y, z)) {
            return false;
        }
        for (int h = 1; h <= headroom; h++) {
            if (!isPassable(x, y + h, z) || grid.isHazard(x, y + h, z)) {
                return false;
            }
        }
        return floor.accepts(grid, x, y, z);
    }

    /** Whether the packed floor block is a cell. */
    public boolean isCell(long key) {
        return isCell(BlockKey.x(key), BlockKey.y(key), BlockKey.z(key));
    }

    /**
     * The gate door whose closed footprint covers the cell's floor or headroom, if any — the cells
     * under a door are tagged with it (road DESIGN §5.4).
     */
    public OptionalInt gateDoor(long key) {
        int x = BlockKey.x(key);
        int y = BlockKey.y(key);
        int z = BlockKey.z(key);
        for (int h = 0; h <= headroom; h++) {
            OptionalInt door = gates.doorAt(x, y + h, z);
            if (door.isPresent()) {
                return door;
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The height difference to the linked cell in a direction: {@code -1}, {@code 0} or {@code +1},
     * or {@link #NO_LINK} when the cell has no link that way. Allocation-free; the linked key is
     * {@code BlockKey.neighbour(key, DX[dir], dy, DZ[dir])}. Assumes {@code key} is a cell.
     */
    public int neighbourDy(long key, int dir) {
        int x = BlockKey.x(key);
        int y = BlockKey.y(key);
        int z = BlockKey.z(key);
        int nx = x + DX[dir];
        int nz = z + DZ[dir];
        int dy = NO_LINK;
        for (int candidate : DY_CANDIDATES) {
            if (isCell(nx, y + candidate, nz)) {
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

    /** The linked cell in a direction, if any. Assumes {@code key} is a cell. */
    public OptionalLong neighbour(long key, int dir) {
        int dy = neighbourDy(key, dir);
        if (dy == NO_LINK) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(BlockKey.neighbour(key, DX[dir], dy, DZ[dir]));
    }

    /**
     * Whether a mover can walk between two adjacent cells that differ in height by one: the lower
     * cell needs a passable block above its headroom (room to jump), unless the upper one is a stair
     * or slab (road DESIGN §5.2). Level moves are always allowed.
     */
    public boolean stepAllowed(int ax, int ay, int az, int bx, int by, int bz) {
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
        int jumpY = ly + headroom + 1;
        return jumpY < grid.maxY() && isPassable(lx, jumpY, lz) && !grid.isHazard(lx, jumpY, lz);
    }

    /**
     * A diagonal link needs an L-shaped walk through one of the two flanking orthogonal cells that
     * ends exactly on the target (so the total height difference is also within one block).
     */
    public boolean cornerPath(long from, int diagonal, long target) {
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
