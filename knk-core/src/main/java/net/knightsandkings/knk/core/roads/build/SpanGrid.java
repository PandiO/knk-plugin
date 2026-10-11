package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.roads.walk.WalkGrid;
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
 *
 * <p>Since KNG-51 Phase A the profile-free walkability logic (span rule, links, step rule, corner rule)
 * lives in {@link WalkGrid}, shared with the last-mile walk search; this class is that grid with the
 * road-material test as its floor test, behaviour unchanged.
 */
public final class SpanGrid {

    public static final int N = WalkGrid.N;
    public static final int NE = WalkGrid.NE;
    public static final int E = WalkGrid.E;
    public static final int SE = WalkGrid.SE;
    public static final int S = WalkGrid.S;
    public static final int SW = WalkGrid.SW;
    public static final int W = WalkGrid.W;
    public static final int NW = WalkGrid.NW;
    public static final int DIRECTIONS = WalkGrid.DIRECTIONS;

    /** x offset per direction (E = +x). */
    public static final int[] DX = WalkGrid.DX;
    /** z offset per direction (S = +z, N = -z). */
    public static final int[] DZ = WalkGrid.DZ;

    /** {@link #neighbourDy} result when there is no link in that direction. */
    public static final int NO_LINK = WalkGrid.NO_LINK;

    /** Passable blocks a span needs above its floor (a player is just under two blocks tall). */
    public static final int HEADROOM = 2;

    private final SurfaceGrid grid;
    private final ProfileSet profiles;
    private final WalkGrid walk;

    public SpanGrid(SurfaceGrid grid, ProfileSet profiles, GateCells gates) {
        this.grid = Objects.requireNonNull(grid, "grid");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(gates, "gates");
        this.walk = new WalkGrid(grid, gates, HEADROOM,
            (g, x, y, z) -> profiles.isRoadMaterial(g.floorMaterial(x, y, z), x, z));
    }

    /** The opposite of a direction ({@code N ↔ S}, {@code NE ↔ SW} …). */
    public static int opposite(int dir) {
        return WalkGrid.opposite(dir);
    }

    /** Whether a direction is one of the four diagonals. */
    public static boolean isDiagonal(int dir) {
        return WalkGrid.isDiagonal(dir);
    }

    public SurfaceGrid surface() {
        return grid;
    }

    public ProfileSet profiles() {
        return profiles;
    }

    /** A block a player can move through: passable in the world, or part of a gate door's closed footprint. */
    public boolean isPassable(int x, int y, int z) {
        return walk.isPassable(x, y, z);
    }

    /** Whether the floor block {@code (x, y, z)} is a road span. */
    public boolean isSpan(int x, int y, int z) {
        return walk.isCell(x, y, z);
    }

    /** Whether the packed floor block is a road span. */
    public boolean isSpan(long key) {
        return walk.isCell(key);
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
        return walk.gateDoor(key);
    }

    /**
     * The height difference to the linked span in a direction: {@code -1}, {@code 0} or {@code +1},
     * or {@link #NO_LINK} when the span has no link that way. Allocation-free; the linked key is
     * {@code BlockKey.neighbour(key, DX[dir], dy, DZ[dir])}. Assumes {@code key} is a span.
     * The jump/stair step rule and the diagonal corner rule are {@link WalkGrid}'s.
     */
    public int neighbourDy(long key, int dir) {
        return walk.neighbourDy(key, dir);
    }

    /** The linked span in a direction, if any. Assumes {@code key} is a span. */
    public OptionalLong neighbour(long key, int dir) {
        return walk.neighbour(key, dir);
    }
}
