package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.util.BlockKey;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The road mask (DESIGN §2): a fixed set of spans with dense indices and the 8-neighbour table
 * restricted to the set. Built once by {@link MaskBuilder}; the distance transform, thinning and
 * graph extraction then run on indices and never ask the world again.
 *
 * <p>Neighbours outside the mask (a span the BFS never reached, one beyond the margin, an ambiguous
 * span filtered out) read as {@link #NONE}, so for every later stage they are non-road — exactly
 * what the distance transform and the thinning rules need. The table is symmetric because
 * {@link SpanGrid}'s links are.
 */
public final class RoadMask {

    /** Neighbour index meaning "no span there". */
    public static final int NONE = -1;

    private final long[] keys;          // sorted ascending, index = span id
    private final int[] neighbours;     // size * 8
    private final int[] gateDoors;      // -1 = none
    private final String[] floors;
    private final int levelCount;

    private RoadMask(long[] keys, int[] neighbours, int[] gateDoors, String[] floors, int levelCount) {
        this.keys = keys;
        this.neighbours = neighbours;
        this.gateDoors = gateDoors;
        this.floors = floors;
        this.levelCount = levelCount;
    }

    /**
     * The mask of exactly these spans (all must be spans of the grid), with the neighbour table
     * limited to them.
     */
    public static RoadMask of(SpanGrid grid, long[] spanKeys) {
        Objects.requireNonNull(grid, "grid");
        long[] keys = spanKeys.clone();
        Arrays.sort(keys);
        int n = dedupeSorted(keys);
        keys = Arrays.copyOf(keys, n);

        int[] neighbours = new int[n * SpanGrid.DIRECTIONS];
        int[] gateDoors = new int[n];
        String[] floors = new String[n];
        for (int i = 0; i < n; i++) {
            long key = keys[i];
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int dy = grid.neighbourDy(key, d);
                int nb = NONE;
                if (dy != SpanGrid.NO_LINK) {
                    nb = Arrays.binarySearch(keys, BlockKey.neighbour(key, SpanGrid.DX[d], dy, SpanGrid.DZ[d]));
                    if (nb < 0) {
                        nb = NONE;
                    }
                }
                neighbours[i * SpanGrid.DIRECTIONS + d] = nb;
            }
            gateDoors[i] = grid.gateDoor(key).orElse(NONE);
            floors[i] = grid.floorMaterial(key);
        }
        return new RoadMask(keys, neighbours, gateDoors, floors, countLevels(keys));
    }

    /** The empty mask. */
    public static RoadMask empty() {
        return new RoadMask(new long[0], new int[0], new int[0], new String[0], 0);
    }

    /**
     * The sub-mask of the spans flagged in {@code keep}; the neighbour table is filtered, not
     * recomputed, so a dropped span becomes non-road for its former neighbours.
     */
    public RoadMask subset(boolean[] keep) {
        if (keep.length != keys.length) {
            throw new IllegalArgumentException("keep has " + keep.length + " flags for " + keys.length + " spans");
        }
        int[] newIndex = new int[keys.length];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            newIndex[i] = keep[i] ? n++ : NONE;
        }
        if (n == keys.length) {
            return this;
        }
        long[] newKeys = new long[n];
        int[] newNeighbours = new int[n * SpanGrid.DIRECTIONS];
        int[] newGates = new int[n];
        String[] newFloors = new String[n];
        for (int i = 0; i < keys.length; i++) {
            int j = newIndex[i];
            if (j == NONE) {
                continue;
            }
            newKeys[j] = keys[i];
            newGates[j] = gateDoors[i];
            newFloors[j] = floors[i];
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = neighbours[i * SpanGrid.DIRECTIONS + d];
                newNeighbours[j * SpanGrid.DIRECTIONS + d] = nb == NONE ? NONE : newIndex[nb];
            }
        }
        return new RoadMask(newKeys, newNeighbours, newGates, newFloors, countLevels(newKeys));
    }

    private static int dedupeSorted(long[] sorted) {
        if (sorted.length == 0) {
            return 0;
        }
        int n = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[n - 1]) {
                sorted[n++] = sorted[i];
            }
        }
        return n;
    }

    private static int countLevels(long[] keys) {
        Map<Long, Integer> perColumn = new HashMap<>();
        int max = 0;
        for (long key : keys) {
            long column = BlockKey.pack(BlockKey.x(key), 0, BlockKey.z(key));
            int count = perColumn.merge(column, 1, Integer::sum);
            if (count > max) {
                max = count;
            }
        }
        return max;
    }

    /** Number of spans. */
    public int size() {
        return keys.length;
    }

    /** Packed floor position of span {@code i}. */
    public long key(int i) {
        return keys[i];
    }

    public int x(int i) {
        return BlockKey.x(keys[i]);
    }

    public int y(int i) {
        return BlockKey.y(keys[i]);
    }

    public int z(int i) {
        return BlockKey.z(keys[i]);
    }

    /** Index of the span at a packed position, or {@link #NONE}. */
    public int indexOf(long key) {
        int i = Arrays.binarySearch(keys, key);
        return i < 0 ? NONE : i;
    }

    /** Index of the span at a floor position, or {@link #NONE}. */
    public int indexOf(int x, int y, int z) {
        return indexOf(BlockKey.pack(x, y, z));
    }

    /** Index of the neighbour of span {@code i} in direction {@code dir}, or {@link #NONE}. */
    public int neighbour(int i, int dir) {
        return neighbours[i * SpanGrid.DIRECTIONS + dir];
    }

    /** How many of the 8 neighbours exist. */
    public int neighbourCount(int i) {
        int count = 0;
        int base = i * SpanGrid.DIRECTIONS;
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            if (neighbours[base + d] != NONE) {
                count++;
            }
        }
        return count;
    }

    /** A span next to non-road (fewer than 8 neighbours) — the distance transform's sources. */
    public boolean isBorder(int i) {
        return neighbourCount(i) < SpanGrid.DIRECTIONS;
    }

    /** Gate door tagged on the span, or {@link #NONE}. */
    public int gateDoor(int i) {
        return gateDoors[i];
    }

    /** Floor material of the span (looking through overlays). */
    public String floor(int i) {
        return floors[i];
    }

    /** Most spans stacked in one (x, z) column (DESIGN §3.3 {@code LevelCount}). */
    public int levelCount() {
        return levelCount;
    }

    /** Euclidean distance between two spans' floor positions. */
    public double distance(int i, int j) {
        double dx = x(i) - x(j);
        double dy = y(i) - y(j);
        double dz = z(i) - z(j);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
