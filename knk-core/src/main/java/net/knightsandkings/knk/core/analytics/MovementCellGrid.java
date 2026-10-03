package net.knightsandkings.knk.core.analytics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MovementCell;

/**
 * Movement samples per heatmap cell of one window (KNG-34 link 7, DESIGN.md D10). A sample is one
 * player's block position at a sampling tick; it is reduced to {@code floor(x / cellSize)},
 * {@code floor(z / cellSize)} at once and only the per-cell count is kept - no player, no trail.
 * Cell indices are packed into one {@code long} per world. Samples come from a timer (at most one per
 * player per {@code world-analytics.movement-sample-seconds}), never from move events, so the boxed
 * lookup key per sample is irrelevant. Not thread-safe: {@link WorldAnalyticsWindow} guards it.
 */
public final class MovementCellGrid {

    private final int cellSize;
    private final Map<String, Map<Long, int[]>> cells = new HashMap<>();
    private int size;

    public MovementCellGrid(int cellSize) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("cellSize must be at least 1 (got: " + cellSize + ")");
        }
        this.cellSize = cellSize;
    }

    public int cellSize() {
        return cellSize;
    }

    /** One sample at a block position. */
    public void sample(String world, int blockX, int blockZ) {
        if (world == null || world.isEmpty()) {
            return;
        }
        long key = pack(Math.floorDiv(blockX, cellSize), Math.floorDiv(blockZ, cellSize));
        Map<Long, int[]> perWorld = cells.computeIfAbsent(world, w -> new HashMap<>());
        int[] count = perWorld.get(key);
        if (count == null) {
            perWorld.put(key, new int[] {1});
            size++;
        } else if (count[0] < Integer.MAX_VALUE) {
            count[0]++;
        }
    }

    /** Distinct cells with samples. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** All cells (world, then insertion order) and clears the grid. */
    public List<MovementCell> drain() {
        List<MovementCell> out = new ArrayList<>(size);
        for (Map.Entry<String, Map<Long, int[]>> world : cells.entrySet()) {
            for (Map.Entry<Long, int[]> cell : world.getValue().entrySet()) {
                long key = cell.getKey();
                out.add(new MovementCell(world.getKey(), cellSize, unpackX(key), unpackZ(key), cell.getValue()[0]));
            }
        }
        cells.clear();
        size = 0;
        return out;
    }

    static long pack(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    static int unpackX(long key) {
        return (int) (key >> 32);
    }

    static int unpackZ(long key) {
        return (int) key;
    }
}
